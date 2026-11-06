package dev.keel.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class BackupTest {
    private val phrase = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
    private val chunk = BackupFile.CHUNK_SIZE
    private val headerSize = 8 + 4 + 16 + 8

    private fun data(size: Int) = ByteArray(size).also { Random(1).nextBytes(it) }

    private fun seal(plain: ByteArray, key: String = phrase) =
        ByteArrayOutputStream().also { BackupFile.write(it, key, plain) }.toByteArray()

    private fun open(file: ByteArray, key: String = phrase) =
        BackupFile.read(ByteArrayInputStream(file), key)

    private fun failure(file: ByteArray, key: String = phrase): String =
        assertThrows(BackupException::class.java) { open(file, key) }.message!!

    @Test fun `data survives a round trip at chunk boundaries`() {
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, chunk * 2, chunk * 3 + 500)) {
            val plain = data(size)
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test fun `the file doesn't contain the data in readable form`() {
        val secret = "my private goal".toByteArray()
        val file = String(seal(secret), Charsets.ISO_8859_1)
        assertFalse("my private goal" in file)
    }

    @Test fun `the same data encrypts differently each time`() {
        val plain = data(1000)
        assertNotEquals(seal(plain).toList(), seal(plain).toList())
    }

    @Test fun `a wrong phrase is rejected with a clear message`() {
        val other = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
        assertTrue("phrase" in failure(seal(data(1000)), other))
    }

    @Test fun `a changed byte anywhere is rejected`() {
        val file = seal(data(chunk * 2 + 100))
        for (position in listOf(headerSize + 10, chunk + 200, file.size - 1)) {
            val altered = file.clone()
            altered[position] = (altered[position].toInt() xor 1).toByte()
            failure(altered)
        }
    }

    @Test fun `a file cut at a chunk boundary is rejected`() {
        val file = seal(data(chunk * 2 + 100))
        val lastChunk = 4 + 100 + 16
        assertTrue("damaged" in failure(file.copyOf(file.size - lastChunk)))
    }

    @Test fun `a file cut mid-chunk is rejected`() {
        val file = seal(data(chunk + 100))
        assertTrue("damaged" in failure(file.copyOf(file.size - 5)))
    }

    @Test fun `reordered chunks are rejected`() {
        val file = seal(data(chunk * 3))
        val sealedChunk = 4 + chunk + 16
        val swapped = file.clone()
        System.arraycopy(file, headerSize, swapped, headerSize + sealedChunk, sealedChunk)
        System.arraycopy(file, headerSize + sealedChunk, swapped, headerSize, sealedChunk)
        failure(swapped)
    }

    @Test fun `other files are recognised as not being backups`() {
        assertTrue("isn't a Keel backup" in failure("{\"format\":\"keel-export\"}".toByteArray()))
        assertTrue("isn't a Keel backup" in failure(ByteArray(0)))
    }

    @Test fun `a bad chunk length is rejected before allocating memory for it`() {
        val file = seal(data(10))
        file[headerSize] = 0x7f
        assertTrue("damaged" in failure(file))
    }

    @Test fun `phrases have the expected shape and don't repeat`() {
        val generated = RecoveryPhrase.generate()
        assertTrue(Regex("[0-9A-Z]{5}(-[0-9A-Z]{5}){4}").matches(generated))
        assertEquals(generated, RecoveryPhrase.normalize(generated)!!.chunked(5).joinToString("-"))
        assertNotEquals(generated, RecoveryPhrase.generate())
    }

    @Test fun `hand-copied phrases are forgiven`() {
        val canonical = "01234-56789-ABCDE-FGHJK-MNPQR"
        val expected = canonical
        assertEquals(expected, RecoveryPhrase.normalize(canonical)!!.chunked(5).joinToString("-"))
        assertEquals(RecoveryPhrase.normalize(canonical), RecoveryPhrase.normalize("o1234 56789 abcde fghjk mnpqr"))
        assertEquals(RecoveryPhrase.normalize(canonical), RecoveryPhrase.normalize("0I234-56789-ABCDE-FGHJK-MNPQR"))
        assertEquals(RecoveryPhrase.normalize(canonical), RecoveryPhrase.normalize("0l234-56789-abcde-fghjk-mnpqr"))
    }

    @Test fun `a hand-typed phrase returns to its standard form`() {
        val typed = "o1234 56789 abcde fghjk mnpqr"
        assertEquals("01234-56789-ABCDE-FGHJK-MNPQR", RecoveryPhrase.format(RecoveryPhrase.normalize(typed)!!))
    }

    @Test fun `impossible phrases are refused`() {
        assertNull(RecoveryPhrase.normalize(""))
        assertNull(RecoveryPhrase.normalize("TOO-SHORT"))
        assertNull(RecoveryPhrase.normalize("UUUUU-UUUUU-UUUUU-UUUUU-UUUUU"))
    }

    @Test fun `the last group is what gets confirmed`() {
        assertEquals("MNPQR", RecoveryPhrase.lastGroup("01234-56789-ABCDE-FGHJK-MNPQR"))
    }
}
