package dev.keel.spike

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests whether encrypted backup can reach any storage provider with no INTERNET permission.
 *
 * Format: 16-byte salt, 8-byte nonce prefix, then chunks of [int length][AES-256-GCM ciphertext].
 * Each chunk's nonce is the prefix plus its index. Its index and an is-last flag are authenticated,
 * so reordered, altered or truncated files are rejected. Chunking keeps memory flat: decrypting one
 * large GCM stream on Android took 42 s for 20 MB because the whole stream is buffered.
 * The payload is generated test data.
 */
object Backup {
    private const val PHRASE = "spike test phrase only"
    private const val CHUNK = 1024 * 1024
    private const val SIZE = 100L * 1024 * 1024
    private const val ITERATIONS = 200_000

    private fun key(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(PHRASE.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array())
        }
    }

    /** Generates the same bytes on every call, one chunk at a time. */
    private class Payload {
        private val random = Random(42)
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        private var left = SIZE
        fun next(): ByteArray {
            val out = ByteArray(minOf(left, CHUNK.toLong()).toInt())
            random.nextBytes(out)
            digest.update(out)
            left -= out.size
            return out
        }
        val done get() = left == 0L
    }

    private fun expectedSha(): String {
        val payload = Payload()
        while (!payload.done) payload.next()
        return hex(payload.digest.digest())
    }

    fun write(context: Context, uri: Uri) {
        val started = System.currentTimeMillis()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val key = key(salt)
        val payload = Payload()
        DataOutputStream(context.contentResolver.openOutputStream(uri)!!.buffered()).use { out ->
            out.write(salt)
            out.write(prefix)
            var index = 0
            while (!payload.done) {
                val plain = payload.next()
                val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, payload.done).doFinal(plain)
                out.writeInt(sealed.size)
                out.write(sealed)
            }
        }
        EventLog.log(context, "backup_written", "bytes=$SIZE,ms=${System.currentTimeMillis() - started},uriAuthority=${uri.authority}")
    }

    /** Returns the SHA-256 of the decrypted data, or throws if the file was altered or cut short. */
    private fun decrypt(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataInputStream(context.contentResolver.openInputStream(uri)!!.buffered()).use { input ->
            val salt = ByteArray(16).also { input.readFully(it) }
            val prefix = ByteArray(8).also { input.readFully(it) }
            val key = key(salt)
            var sealed = readChunk(input) ?: error("Backup has no data")
            var index = 0
            while (true) {
                val next = readChunk(input)
                digest.update(cipher(Cipher.DECRYPT_MODE, key, prefix, index++, next == null).doFinal(sealed))
                sealed = next ?: break
            }
        }
        return hex(digest.digest())
    }

    private fun readChunk(input: DataInputStream): ByteArray? {
        val length = try { input.readInt() } catch (_: EOFException) { return null }
        check(length in 1..(CHUNK + 16)) { "Bad chunk length" }
        return ByteArray(length).also { input.readFully(it) }
    }

    fun restore(context: Context, uri: Uri) {
        val started = System.currentTimeMillis()
        val match = decrypt(context, uri) == expectedSha()
        EventLog.log(context, "backup_restored", "match=$match,ms=${System.currentTimeMillis() - started},uriAuthority=${uri.authority}")
    }

    /** Altered, truncated and intact files, without the file picker. */
    fun selfTest(context: Context) {
        val file = File(context.cacheDir, "selftest.bin")
        val uri = Uri.fromFile(file)
        write(context, uri)
        val expected = expectedSha()
        val intact = decrypt(context, uri) == expected

        RandomAccessFile(file, "rw").use { f ->
            f.seek(5_000_000)
            val b = f.read()
            f.seek(5_000_000)
            f.write(b xor 1)
        }
        val alteredRejected = runCatching { decrypt(context, uri) }.isFailure

        write(context, uri)
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - (CHUNK + 16 + 4)) }
        val truncatedRejected = runCatching { decrypt(context, uri) }.isFailure

        EventLog.log(context, "backup_selftest", "intact=$intact,alteredRejected=$alteredRejected,truncatedRejected=$truncatedRejected")
        file.delete()
    }
}
