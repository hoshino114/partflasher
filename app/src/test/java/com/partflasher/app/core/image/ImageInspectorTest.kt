package com.partflasher.app.core.image

import com.partflasher.app.core.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageInspectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val inspector = ImageInspector()

    // ------------------------------------------------------------------ boot

    @Test
    fun detectsAndroidBootV4AndParsesSizes() {
        val bytes = ByteArray(4096)
        putAscii(bytes, 0, "ANDROID!")
        putIntLe(bytes, 8, 8 * 1024 * 1024) // kernel_size
        putIntLe(bytes, 12, 2 * 1024 * 1024) // ramdisk_size (v3+)
        putIntLe(bytes, 20, 1584) // header_size
        putIntLe(bytes, 40, 4) // header_version

        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.ANDROID_BOOT, result.format)
        assertEquals(4, result.bootHeaderVersion)
        assertTrue(result.details.contains("header v4"))
        assertTrue(result.details.contains("内核"))
    }

    @Test
    fun detectsAndroidBootV3() {
        val bytes = ByteArray(2048)
        putAscii(bytes, 0, "ANDROID!")
        putIntLe(bytes, 20, 1580)
        putIntLe(bytes, 40, 3)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.ANDROID_BOOT, result.format)
        assertEquals(3, result.bootHeaderVersion)
    }

    @Test
    fun truncatedBootHeaderDoesNotCrash() {
        val bytes = "ANDROID!".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.ANDROID_BOOT, result.format)
        assertNull(result.bootHeaderVersion)
        assertTrue(result.details.contains("未识别"))
    }

    @Test
    fun bootHeaderSizeBeyondFileIsReportedAsIncomplete() {
        val bytes = ByteArray(100)
        putAscii(bytes, 0, "ANDROID!")
        putIntLe(bytes, 20, 1584)
        putIntLe(bytes, 40, 4)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.ANDROID_BOOT, result.format)
        assertTrue(result.details.contains("头部不完整"))
    }

    @Test
    fun detectsVendorBootV4() {
        val bytes = ByteArray(4096)
        putAscii(bytes, 0, "VNDRBOOT")
        putIntLe(bytes, 8, 4)
        putIntLe(bytes, 24, 1024 * 1024)
        putIntLe(bytes, 2096, 2124)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.VENDOR_BOOT, result.format)
        assertEquals(4, result.bootHeaderVersion)
        assertTrue(result.details.contains("header v4"))
    }

    // ------------------------------------------------------------------ other containers

    @Test
    fun detectsDtboAndParsesEntryCount() {
        val bytes = ByteArray(512)
        putIntLe(bytes, 0, 0xD7B7AB1E.toInt())
        putIntLe(bytes, 4, 4096)
        putIntLe(bytes, 8, 32)
        putIntLe(bytes, 12, 24)
        putIntLe(bytes, 16, 3)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.DTBO, result.format)
        assertTrue(result.details.contains("3 个设备树条目"))
    }

    @Test
    fun detectsVbmetaAndParsesLayoutOffsets() {
        val bytes = ByteArray(4096)
        putAscii(bytes, 0, "AVB0")
        // AVB fields are big-endian (network byte order).
        putIntBe(bytes, 4, 1)
        putIntBe(bytes, 8, 0)
        // Packed AvbVBMetaImageHeader: flags u32 @120, release_string @128.
        putIntBe(bytes, 120, 0x3)
        putAscii(bytes, 128, "avbtool 1.2.0")
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.VBMETA, result.format)
        assertTrue("details=${result.details}", result.details.contains("v1.0"))
        assertTrue("details=${result.details}", result.details.contains("flags=0x3"))
        assertTrue("details=${result.details}", result.details.contains("avbtool 1.2.0"))
    }

    @Test
    fun parsesRealAvbtoolGeneratedHeaderLayout() {
        // 256-byte vbmeta header generated with AOSP avbtool.py
        // (`make_vbmeta_image --flags 2 --rollback_index 7`). Layout in the
        // packed AvbVBMetaImageHeader: rollback_index @112 = 7, flags @120 = 2,
        // rollback_index_location @124 = 0, release_string @128 = "avbtool 1.3.0".
        val hex = "4156423000000001000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000070000000200000000" +
            "617662746f6f6c20312e332e3000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(256, bytes.size)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.VBMETA, result.format)
        assertTrue("details=${result.details}", result.details.contains("v1.0"))
        assertTrue("details=${result.details}", result.details.contains("flags=0x2"))
        assertTrue("details=${result.details}", result.details.contains("avbtool 1.3.0"))
    }

    @Test
    fun detectsSparseAndComputesExpandedSize() {
        val bytes = ByteArray(28)
        putIntLe(bytes, 0, 0xED26FF3A.toInt())
        putShortLe(bytes, 4, 1)
        putShortLe(bytes, 6, 0)
        putShortLe(bytes, 8, 28)
        putShortLe(bytes, 10, 12)
        putIntLe(bytes, 12, 4096) // blk_sz
        putIntLe(bytes, 16, 1024) // total_blks
        putIntLe(bytes, 20, 5) // total_chunks
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.SPARSE, result.format)
        assertEquals(4L * 1024L * 1024L, result.sparseExpandedBytes)
        assertTrue(result.details.contains("展开后"))
    }

    @Test
    fun detectsExt4MagicAt0x438() {
        val bytes = ByteArray(4096)
        putShortLe(bytes, 0x438, 0xEF53)
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.EXT4, result.format)
        assertEquals("ext4 文件系统镜像", result.formatLabel)
    }

    @Test
    fun detectsErofsMagicAt1024() {
        val bytes = ByteArray(4096)
        putIntLe(bytes, 1024, 0xE0F5E1E2.toInt())
        val result = inspector.inspect(bytes)
        assertEquals(ImageFormat.EROFS, result.format)
    }

    @Test
    fun detectsCompressedAndPayloadContainers() {
        assertEquals(ImageFormat.GZIP, inspector.inspect(byteArrayOf(0x1F, 0x8B.toByte())).format)
        assertEquals(
            ImageFormat.LZ4,
            inspector.inspect(byteArrayOf(0x04, 0x22, 0x4D, 0x18)).format,
        )
        assertEquals(
            ImageFormat.ZSTD,
            inspector.inspect(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())).format,
        )
        assertEquals(
            ImageFormat.PAYLOAD,
            inspector.inspect("CrAU".toByteArray(Charsets.US_ASCII)).format,
        )
    }

    @Test
    fun emptyAndUnknownFilesAreHandledGracefully() {
        val empty = inspector.inspect(ByteArray(0))
        assertEquals(ImageFormat.UNKNOWN, empty.format)
        assertTrue(empty.details.contains("文件为空"))

        val unknown = inspector.inspect(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        assertEquals(ImageFormat.UNKNOWN, unknown.format)
        assertTrue(unknown.details.contains("无法识别"))
    }

    // ------------------------------------------------------------------ file based

    @Test
    fun inspectFileReadsHeaderAndUsesRealFileSize() {
        val header = ByteArray(4096)
        putAscii(header, 0, "ANDROID!")
        putIntLe(header, 40, 4)
        putIntLe(header, 20, 1584)
        val file = temporaryFolder.newFile("boot.img")
        file.writeBytes(header + ByteArray(64 * 1024))

        val result = inspector.inspect(file)
        assertEquals(ImageFormat.ANDROID_BOOT, result.format)
        assertEquals(4, result.bootHeaderVersion)
    }

    @Test
    fun inspectMissingFileReturnsUnknownWithoutThrowing() {
        val result = inspector.inspect(File(temporaryFolder.root, "missing.img"))
        assertEquals(ImageFormat.UNKNOWN, result.format)
        assertTrue(result.details.contains("文件不存在"))
    }

    @Test
    fun inspectEmptyFileReportsZeroBytes() {
        val file = temporaryFolder.newFile("empty.img")
        file.writeBytes(ByteArray(0))
        val result = inspector.inspect(file)
        assertEquals(ImageFormat.UNKNOWN, result.format)
        assertTrue(result.details.contains("0 字节"))
    }

    @Test
    fun inspectDirectoryReturnsUnknownWithoutThrowing() {
        val dir = temporaryFolder.newFolder("adir")
        val result = inspector.inspect(dir)
        assertEquals(ImageFormat.UNKNOWN, result.format)
        assertTrue(result.details.contains("不是普通文件"))
    }

    // ------------------------------------------------------------------ helpers

    private fun putAscii(target: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(target, offset)
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun putShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putIntBe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
