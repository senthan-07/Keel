package dev.keel.domain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/** A 25-character recovery phrase (125 random bits), in an alphabet with no look-alike letters. */
object RecoveryPhrase {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 25
    private const val GROUP = 5

    fun generate(random: SecureRandom = SecureRandom()): String =
        (0 until LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(GROUP).joinToString("-") { it.joinToString("") }

    /**
     * Forgives what people do when copying a phrase by hand: lower case, missing dashes and spaces,
     * and O, I or L typed for 0 or 1. Returns null if it can't be a valid phrase.
     */
    fun normalize(input: String): String? {
        val cleaned = input.uppercase().filter { it.isLetterOrDigit() }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** The standard form with dashes, from a normalized phrase. */
    fun format(normalized: String): String = normalized.chunked(GROUP).joinToString("-")

    /** The last group, which is asked for to check the phrase really was written down. */
    fun lastGroup(phrase: String): String = phrase.substringAfterLast('-')
}

/**
 * Encrypted backup file: magic, iteration count, 16-byte salt, 8-byte nonce prefix, then chunks of
 * [int length][AES-256-GCM ciphertext].
 *
 * Each chunk's nonce is the prefix plus its index, and its index and an is-last flag are
 * authenticated, so altered, reordered or truncated files are rejected. Chunks keep decryption fast:
 * one large GCM stream took 42 s for 20 MB on the test phone, and 100 MB of chunks about 3 s.
 */
object BackupFile {
    private val MAGIC = "KEELBK1\n".toByteArray(Charsets.US_ASCII)
    const val CHUNK_SIZE = 1024 * 1024
    private const val TAG_BYTES = 16
    private const val ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 5_000_000

    /** [phrase] must already be normalized. */
    fun write(out: OutputStream, phrase: String, data: ByteArray, random: SecureRandom = SecureRandom()) {
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val key = deriveKey(phrase, salt, ITERATIONS)
        val stream = DataOutputStream(out)
        stream.write(MAGIC)
        stream.writeInt(ITERATIONS)
        stream.write(salt)
        stream.write(prefix)
        var index = 0
        var offset = 0
        do {
            val size = minOf(CHUNK_SIZE, data.size - offset)
            val last = offset + size == data.size
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last).doFinal(data, offset, size)
            stream.writeInt(sealed.size)
            stream.write(sealed)
            offset += size
            index++
        } while (!last)
        stream.flush()
    }

    /** Returns the decrypted data, or throws [BackupException] with a message fit to show. */
    fun read(input: InputStream, phrase: String): ByteArray {
        val stream = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            stream.readFully(magic)
        } catch (_: EOFException) {
            throw BackupException("This isn't a Keel backup file.")
        }
        if (!magic.contentEquals(MAGIC)) throw BackupException("This isn't a Keel backup file.")
        try {
            val iterations = stream.readInt()
            if (iterations !in 1..MAX_ITERATIONS) throw BackupException(DAMAGED)
            val salt = ByteArray(16).also(stream::readFully)
            val prefix = ByteArray(8).also(stream::readFully)
            val key = deriveKey(phrase, salt, iterations)
            val output = java.io.ByteArrayOutputStream()
            var sealed = readChunk(stream) ?: throw BackupException(DAMAGED)
            var index = 0
            while (true) {
                val next = readChunk(stream)
                val plain = try {
                    cipher(Cipher.DECRYPT_MODE, key, prefix, index, next == null).doFinal(sealed)
                } catch (_: GeneralSecurityException) {
                    throw BackupException(if (index == 0) WRONG_PHRASE else DAMAGED)
                }
                output.write(plain)
                index++
                sealed = next ?: break
            }
            return output.toByteArray()
        } catch (_: EOFException) {
            throw BackupException(DAMAGED)
        } catch (error: IOException) {
            throw BackupException("Couldn't read the file: ${error.message}")
        }
    }

    private fun readChunk(stream: DataInputStream): ByteArray? {
        val length = try {
            stream.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length !in TAG_BYTES..(CHUNK_SIZE + TAG_BYTES)) throw BackupException(DAMAGED)
        return ByteArray(length).also(stream::readFully)
    }

    private fun deriveKey(phrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(phrase.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(ByteBuffer.allocate(5).putInt(index).put((if (last) 1 else 0).toByte()).array())
        }
    }

    private const val WRONG_PHRASE = "That recovery phrase doesn't open this backup, or the file is damaged."
    private const val DAMAGED = "This backup file is damaged or incomplete."
}
