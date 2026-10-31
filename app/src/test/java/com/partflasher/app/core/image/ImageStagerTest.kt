package com.partflasher.app.core.image

import com.partflasher.app.core.util.FileHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStagerTest {

    @Test
    fun safeStagingNameKeepsNormalNameAndAddsTimestampPrefix() {
        val name = ImageStager.safeStagingName("boot.img", 1234567890L)
        assertEquals("img_1234567890_boot.img", name)
    }

    @Test
    fun safeStagingNameReplacesPathSeparatorsAndTraversal() {
        val name = ImageStager.safeStagingName("../../etc/passwd", 42L)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertTrue(name.startsWith("img_42_"))
    }

    @Test
    fun safeStagingNameReplacesShellAndUnicodeCharacters() {
        val name = ImageStager.safeStagingName("镜像 文件'; rm -rf /.img", 7L)
        assertTrue(name, name.matches(Regex("""img_7_[A-Za-z0-9._-]+""")))
        assertFalse(name.contains("'"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains(";"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun safeStagingNameAddsImgExtensionWhenMissing() {
        val name = ImageStager.safeStagingName("boot", 100L)
        assertTrue(name.endsWith(".img"))
        assertEquals("img_100_boot.img", name)
    }

    @Test
    fun safeStagingNameUsesFallbackForBlankInput() {
        assertEquals("img_5_image.img", ImageStager.safeStagingName("", 5L))
        assertEquals("img_6_image.img", ImageStager.safeStagingName("   ", 6L))
    }

    @Test
    fun safeStagingNameTruncatesLongNames() {
        val longName = "a".repeat(500) + ".img"
        val name = ImageStager.safeStagingName(longName, 9L)
        assertTrue(name.length < 120)
        assertTrue(name.endsWith(".img"))
    }

    @Test
    fun safeStagingNameIsDeterministicForSameInputs() {
        assertEquals(
            ImageStager.safeStagingName("recovery.img", 11L),
            ImageStager.safeStagingName("recovery.img", 11L),
        )
    }

    @Test
    fun secondPassHashIsLimitedToImagesUpTo256MiB() {
        val mib = 1024L * 1024L
        assertEquals(256L * mib, ImageStager.MAX_SECOND_PASS_HASH_BYTES)
        assertFalse(ImageStager.shouldVerifyStagedHash(0L))
        assertFalse(ImageStager.shouldVerifyStagedHash(-1L))
        assertTrue(ImageStager.shouldVerifyStagedHash(1L))
        assertTrue(ImageStager.shouldVerifyStagedHash(64L * mib))
        assertTrue(ImageStager.shouldVerifyStagedHash(256L * mib))
        assertFalse(ImageStager.shouldVerifyStagedHash(256L * mib + 1L))
        assertFalse(ImageStager.shouldVerifyStagedHash(2L * 1024L * mib))
    }

    @Test
    fun stagedHashComparisonIsCaseInsensitiveAndLengthChecked() {
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(ImageStager.isSameSha256(digest, digest.uppercase()))
        assertTrue(ImageStager.isSameSha256(digest.uppercase(), digest))
        assertFalse(ImageStager.isSameSha256(digest, digest.dropLast(1) + "0"))
        assertFalse(ImageStager.isSameSha256(digest, digest.dropLast(1)))
        assertFalse(ImageStager.isSameSha256("", digest))
    }

    @Test
    fun nativeBackendNameIsExposedForStagingLog() {
        assertTrue(FileHasher.backendName().isNotBlank())
    }

}
