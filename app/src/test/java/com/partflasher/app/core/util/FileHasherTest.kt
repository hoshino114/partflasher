package com.partflasher.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class FileHasherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val hasher = FileHasher()

    @Test
    fun hashesAbcKnownVector() {
        val file = temporaryFolder.newFile("abc.txt").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hasher.sha256(file),
        )
    }

    @Test
    fun hashesEmptyFileKnownVector() {
        val file = temporaryFolder.newFile("empty.txt").apply { writeBytes(ByteArray(0)) }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hasher.sha256(file),
        )
    }

    @Test
    fun hashesMultiBufferFileMatchingMessageDigest() {
        // Larger than the 1 MiB read buffer to exercise the streaming loop.
        val data = ByteArray(3 * 1024 * 1024 + 123)
        var seed = 0x12345678
        for (index in data.indices) {
            seed = seed * 1103515245 + 12345
            data[index] = (seed ushr 16).toByte()
        }
        val file = temporaryFolder.newFile("big.img").apply { writeBytes(data) }
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it)
        }
        val actual = hasher.sha256(file)
        assertEquals(expected, actual)
        assertEquals(64, actual.length)
        assertNotEquals(0, actual.length)
    }

    @Test
    fun newSha256DigestProducesSameHex() {
        val digest = hasher.newSha256Digest()
        digest.update("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileHasher.toHex(digest.digest()),
        )
    }

    @Test
    fun toHexFormatsBytesAsLowercase() {
        assertEquals("00010f10ff", FileHasher.toHex(byteArrayOf(0, 1, 0x0F, 0x10, 0xFF.toByte())))
        assertEquals("", FileHasher.toHex(ByteArray(0)))
    }

    @Test
    fun missingFileThrowsFileNotFound() {
        try {
            hasher.sha256(File(temporaryFolder.root, "nope.img"))
            org.junit.Assert.fail("expected FileNotFoundException")
        } catch (expected: FileNotFoundException) {
            assert(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun differentContentProducesDifferentHash() {
        val first = temporaryFolder.newFile("a.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = temporaryFolder.newFile("b.bin").apply { writeBytes(byteArrayOf(1, 2, 4)) }
        assertNotEquals(hasher.sha256(first), hasher.sha256(second))
    }

    @Test
    fun backendNameIsAvailableForDiagnostics() {
        assert(FileHasher.backendName().isNotBlank())
    }
}
