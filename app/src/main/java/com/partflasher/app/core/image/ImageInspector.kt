package com.partflasher.app.core.image

import com.partflasher.app.core.model.ImageFormat
import com.partflasher.app.core.model.ImageMetadata
import com.partflasher.app.core.util.Format
import java.io.File

/**
 * Header-level IMG inspection. Pure JVM, safe to unit test.
 *
 * Only the first [ImageHeaderParser.MAX_HEADER_BYTES] bytes are ever read, so
 * inspecting a multi-gigabyte image costs almost nothing.
 */
class ImageInspector {

    fun inspect(file: File): ImageMetadata {
        if (!file.exists()) {
            return ImageMetadata(ImageFormat.UNKNOWN, UNKNOWN_LABEL, "文件不存在：${file.name}")
        }
        if (!file.isFile) {
            return ImageMetadata(ImageFormat.UNKNOWN, UNKNOWN_LABEL, "不是普通文件：${file.name}")
        }
        val header = try {
            readHeader(file)
        } catch (e: java.io.IOException) {
            return ImageMetadata(
                ImageFormat.UNKNOWN,
                UNKNOWN_LABEL,
                "无法读取文件头：${e.message ?: e.javaClass.simpleName}",
            )
        }
        return ImageHeaderParser.inspect(header, file.length())
    }

    fun inspect(bytes: ByteArray): ImageMetadata =
        ImageHeaderParser.inspect(bytes, bytes.size.toLong())

    private fun readHeader(file: File): ByteArray {
        val toRead = minOf(file.length(), ImageHeaderParser.MAX_HEADER_BYTES.toLong()).toInt()
        if (toRead <= 0) return ByteArray(0)
        val buffer = ByteArray(toRead)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < toRead) {
                val read = input.read(buffer, offset, toRead - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == toRead) buffer else buffer.copyOf(offset)
        }
    }

    private companion object {
        const val UNKNOWN_LABEL = "未知格式"
    }
}

/** Parses the fixed-layout IMG headers into [ImageMetadata]. Internal, pure. */
internal object ImageHeaderParser {

    const val MAX_HEADER_BYTES = 4096

    private const val BOOT_MAGIC = "ANDROID!"
    private const val VENDOR_BOOT_MAGIC = "VNDRBOOT"
    private const val AVB_MAGIC = "AVB0"
    private const val PAYLOAD_MAGIC = "CrAU"
    private const val DTBO_MAGIC = 0xD7B7AB1E.toInt()
    private const val SPARSE_MAGIC = 0xED26FF3A.toInt()
    private const val EXT4_MAGIC = 0xEF53
    private const val EROFS_MAGIC = 0xE0F5E1E2.toInt()

    private const val EXT4_SUPERBLOCK_OFFSET = 1024
    private const val EXT4_MAGIC_OFFSET = EXT4_SUPERBLOCK_OFFSET + 56 // 0x438
    private const val EROFS_SUPERBLOCK_OFFSET = 1024

    fun inspect(header: ByteArray, fileSize: Long): ImageMetadata {
        if (fileSize <= 0L) {
            return ImageMetadata(ImageFormat.UNKNOWN, "未知格式", "文件为空（0 字节）")
        }
        if (header.isEmpty()) {
            return ImageMetadata(ImageFormat.UNKNOWN, "未知格式", "无法读取文件头")
        }
        if (matchesAscii(header, 0, BOOT_MAGIC)) return bootMetadata(header, fileSize)
        if (matchesAscii(header, 0, VENDOR_BOOT_MAGIC)) return vendorBootMetadata(header, fileSize)
        if (intAt(header, 0) == DTBO_MAGIC) return dtboMetadata(header)
        if (matchesAscii(header, 0, AVB_MAGIC)) return vbmetaMetadata(header)
        if (intAt(header, 0) == SPARSE_MAGIC) return sparseMetadata(header, fileSize)
        if (matchesAscii(header, 0, PAYLOAD_MAGIC)) {
            return ImageMetadata(
                ImageFormat.PAYLOAD,
                "Android OTA payload 镜像",
                "检测到 Android OTA payload（CrAU）容器",
            )
        }
        if (isGzip(header)) {
            return ImageMetadata(ImageFormat.GZIP, "gzip 压缩镜像", "检测到 gzip 压缩数据（1F 8B）")
        }
        if (isLz4(header)) {
            return ImageMetadata(ImageFormat.LZ4, "LZ4 压缩镜像", "检测到 LZ4 压缩数据")
        }
        if (isZstd(header)) {
            return ImageMetadata(ImageFormat.ZSTD, "Zstandard 压缩镜像", "检测到 Zstandard 压缩数据")
        }
        if (u16At(header, EXT4_MAGIC_OFFSET) == EXT4_MAGIC) {
            return ImageMetadata(
                ImageFormat.EXT4,
                "ext4 文件系统镜像",
                "检测到 ext4 超级块（magic 0xEF53，偏移 0x438）",
            )
        }
        if (intAt(header, EROFS_SUPERBLOCK_OFFSET) == EROFS_MAGIC) {
            return ImageMetadata(
                ImageFormat.EROFS,
                "EROFS 文件系统镜像",
                "检测到 EROFS 超级块（偏移 1024）",
            )
        }
        return ImageMetadata(
            ImageFormat.UNKNOWN,
            "未知格式",
            "无法识别的文件头（前 16 字节：${hexPreview(header)}，文件大小 ${Format.bytes(fileSize)}）",
        )
    }

    // ------------------------------------------------------------------ boot family

    private fun bootMetadata(header: ByteArray, fileSize: Long): ImageMetadata {
        val rawVersion = intAt(header, 40)
        val version = rawVersion?.takeIf { it in 0..4 }
        val builder = StringBuilder("Android boot 镜像")
        if (version == null) {
            builder.append("（未识别的 header 版本）")
        } else {
            builder.append("（header v").append(version)
            val kernelSize = unsignedIntAt(header, 8)
            if (kernelSize != null && kernelSize > 0L) {
                builder.append("，内核 ").append(Format.bytes(kernelSize))
            }
            val ramdiskOffset = if (version >= 3) 12 else 16
            val ramdiskSize = unsignedIntAt(header, ramdiskOffset)
            if (ramdiskSize != null && ramdiskSize > 0L) {
                builder.append("，ramdisk ").append(Format.bytes(ramdiskSize))
            }
            if (version >= 3) {
                val headerSize = intAt(header, 20)
                val expected = if (version == 3) 1580 else 1584
                when {
                    headerSize == null || headerSize !in 64..MAX_HEADER_BYTES ->
                        builder.append("，header_size 异常")

                    headerSize.toLong() > fileSize ->
                        builder.append("，头部不完整（声明 $headerSize 字节，文件仅 $fileSize 字节）")

                    headerSize != expected -> builder.append("，header_size=$headerSize")
                }
            }
            builder.append("）")
        }
        return ImageMetadata(
            format = ImageFormat.ANDROID_BOOT,
            formatLabel = "Android boot 镜像",
            details = builder.toString(),
            bootHeaderVersion = version,
        )
    }

    private fun vendorBootMetadata(header: ByteArray, fileSize: Long): ImageMetadata {
        val version = intAt(header, 8)
        val builder = StringBuilder("vendor_boot 镜像")
        if (version == null) {
            builder.append("（header 不完整）")
        } else {
            builder.append("（header v").append(version)
            val ramdiskSize = unsignedIntAt(header, 24)
            if (ramdiskSize != null && ramdiskSize > 0L) {
                builder.append("，vendor ramdisk ").append(Format.bytes(ramdiskSize))
            }
            val headerSize = intAt(header, 2096)
            val expected = if (version >= 4) 2124 else 2108
            when {
                headerSize == null -> Unit
                headerSize !in 64..MAX_HEADER_BYTES -> builder.append("，header_size 异常")
                headerSize.toLong() > fileSize ->
                    builder.append("，头部不完整（声明 $headerSize 字节，文件仅 $fileSize 字节）")

                headerSize != expected -> builder.append("，header_size=$headerSize")
            }
            builder.append("）")
        }
        return ImageMetadata(
            format = ImageFormat.VENDOR_BOOT,
            formatLabel = "vendor_boot 镜像",
            details = builder.toString(),
            bootHeaderVersion = version,
        )
    }

    private fun dtboMetadata(header: ByteArray): ImageMetadata {
        val totalSize = unsignedIntAt(header, 4)
        val headerSize = unsignedIntAt(header, 8)
        val entrySize = unsignedIntAt(header, 12)
        val entryCount = unsignedIntAt(header, 16)
        val builder = StringBuilder("dtbo 镜像")
        if (entryCount != null && entrySize != null) {
            builder.append("（").append(entryCount).append(" 个设备树条目")
            if (totalSize != null) builder.append("，总大小 ").append(Format.bytes(totalSize))
            if (headerSize != null) builder.append("，header_size=").append(headerSize)
            builder.append("）")
        }
        return ImageMetadata(ImageFormat.DTBO, "dtbo 镜像", builder.toString())
    }

    private fun vbmetaMetadata(header: ByteArray): ImageMetadata {
        // AVB fields are stored in NETWORK byte order (big-endian), unlike the
        // Android boot/dtbo/sparse headers which are little-endian.
        val major = intAtBigEndian(header, 4)
        val minor = intAtBigEndian(header, 8)
        // Packed AvbVBMetaImageHeader: magic(4) + 2*u32(8) + 2*u64(16) +
        // u32 algorithm(4) + 11*u64(88) = 120 -> flags u32, then
        // rollback_index_location u32 @124, release_string[48] @128..175,
        // reserved[80] @176..255 (total header size 256).
        val flags = intAtBigEndian(header, 120)
        val release = readCString(header, 128, 48)
        val builder = StringBuilder("vbmeta 镜像（AVB ")
        builder.append(if (major != null && minor != null) "v$major.$minor" else "版本未知")
        if (flags != null) builder.append("，flags=0x").append(Integer.toHexString(flags))
        if (release.isNotBlank()) builder.append("，release=").append(release)
        builder.append("）")
        return ImageMetadata(ImageFormat.VBMETA, "vbmeta 镜像", builder.toString())
    }

    private fun sparseMetadata(header: ByteArray, fileSize: Long): ImageMetadata {
        val major = u16At(header, 4)
        val minor = u16At(header, 6)
        val fileHeaderSize = u16At(header, 8)
        val chunkHeaderSize = u16At(header, 10)
        val blockSize = unsignedIntAt(header, 12)
        val totalBlocks = unsignedIntAt(header, 16)
        val totalChunks = unsignedIntAt(header, 20)

        val builder = StringBuilder("Android sparse 镜像")
        if (major != null && minor != null) builder.append("（v$major.$minor")
        val expanded = if (blockSize != null && totalBlocks != null && blockSize > 0L && totalBlocks > 0L &&
            blockSize <= 16L * 1024L * 1024L && totalBlocks <= Long.MAX_VALUE / blockSize
        ) {
            blockSize * totalBlocks
        } else {
            null
        }
        if (expanded != null) builder.append("，展开后 ").append(Format.bytes(expanded))
        if (totalBlocks != null) builder.append("，共 ").append(totalBlocks).append(" 块")
        if (totalChunks != null) builder.append("，").append(totalChunks).append(" 个数据块")
        if (fileHeaderSize != null && fileHeaderSize != 28) builder.append("，文件头长度异常（$fileHeaderSize）")
        if (chunkHeaderSize != null && chunkHeaderSize != 12) builder.append("，块头长度异常（$chunkHeaderSize）")
        if (fileSize > 0L && fileHeaderSize != null && fileSize < fileHeaderSize) {
            builder.append("，头部不完整")
        }
        builder.append("）")
        return ImageMetadata(
            format = ImageFormat.SPARSE,
            formatLabel = "Android sparse 镜像",
            details = builder.toString(),
            sparseExpandedBytes = expanded,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun isGzip(header: ByteArray): Boolean =
        header.size >= 2 && byteAt(header, 0) == 0x1F && byteAt(header, 1) == 0x8B

    private fun isLz4(header: ByteArray): Boolean =
        matchesBytes(header, 0, byteArrayOf(0x04, 0x22, 0x4D, 0x18)) ||
            matchesBytes(header, 0, byteArrayOf(0x02, 0x21, 0x4C, 0x18))

    private fun isZstd(header: ByteArray): Boolean =
        matchesBytes(header, 0, byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))

    private fun matchesAscii(header: ByteArray, offset: Int, value: String): Boolean =
        matchesBytes(header, offset, value.toByteArray(Charsets.US_ASCII))

    private fun matchesBytes(header: ByteArray, offset: Int, value: ByteArray): Boolean {
        if (offset < 0 || header.size < offset + value.size) return false
        for (index in value.indices) {
            if (header[offset + index] != value[index]) return false
        }
        return true
    }

    private fun byteAt(header: ByteArray, offset: Int): Int? =
        if (offset in header.indices) header[offset].toInt() and 0xFF else null

    private fun intAt(header: ByteArray, offset: Int): Int? {
        if (offset < 0 || header.size < offset + 4) return null
        return (header[offset].toInt() and 0xFF) or
            ((header[offset + 1].toInt() and 0xFF) shl 8) or
            ((header[offset + 2].toInt() and 0xFF) shl 16) or
            ((header[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun intAtBigEndian(header: ByteArray, offset: Int): Int? {
        if (offset < 0 || header.size < offset + 4) return null
        return ((header[offset].toInt() and 0xFF) shl 24) or
            ((header[offset + 1].toInt() and 0xFF) shl 16) or
            ((header[offset + 2].toInt() and 0xFF) shl 8) or
            (header[offset + 3].toInt() and 0xFF)
    }

    private fun unsignedIntAt(header: ByteArray, offset: Int): Long? =
        intAt(header, offset)?.toLong()?.and(0xFFFF_FFFFL)

    private fun u16At(header: ByteArray, offset: Int): Int? {
        if (offset < 0 || header.size < offset + 2) return null
        return (header[offset].toInt() and 0xFF) or ((header[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readCString(header: ByteArray, offset: Int, maxLength: Int): String {
        if (offset < 0 || offset >= header.size) return ""
        var end = offset
        val limit = minOf(offset + maxLength, header.size)
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun hexPreview(header: ByteArray): String =
        header.take(16).joinToString(" ") { "%02x".format(it) }
}
