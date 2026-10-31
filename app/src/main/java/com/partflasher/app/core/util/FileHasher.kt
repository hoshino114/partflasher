package com.partflasher.app.core.util

import com.partflasher.app.core.log.LogRepository
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Streaming SHA-256 helper. Uses the optional arm64/x86_64 native implementation
 * when available and falls back to [java.security.MessageDigest] otherwise.
 */
class FileHasher(
    private val log: LogRepository? = null,
) {
    /** Returns the lowercase SHA-256 hex digest of [file]. */
    fun sha256(file: File): String {
        if (!file.isFile) {
            throw FileNotFoundException("无法计算 SHA-256：文件不存在或不可读（${file.path}）")
        }
        NativeSha256.sha256OrNull(file)?.let { nativeDigest ->
            log?.debug(TAG, "使用原生哈希（${NativeSha256.backendName()}）计算 SHA-256：${file.name}")
            return nativeDigest.lowercase()
        }
        log?.debug(TAG, "原生哈希不可用，使用 JVM MessageDigest 计算 SHA-256：${file.name}")
        val digest = newSha256Digest()
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /** Creates a SHA-256 digest so callers can hash data while copying it (one pass). */
    fun newSha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    companion object {
        private const val TAG = "Hash"
        private const val BUFFER_SIZE = 1024 * 1024

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            var index = 0
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                out[index++] = HEX_CHARS[value ushr 4]
                out[index++] = HEX_CHARS[value and 0x0F]
            }
            return String(out)
        }

        /** Name of the active hashing backend, for diagnostics. */
        fun backendName(): String = NativeSha256.backendName()
    }
}
