package com.partflasher.app.core.image

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.partflasher.app.core.log.LogRepository
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.util.FileHasher
import com.partflasher.app.core.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copies a SAF-picked image into app-private staging storage, computes its
 * SHA-256 and inspects its header. The staged file is valid only for the
 * lifetime of the app process.
 *
 * The copy is streamed once: bytes are written to the cache file and fed to the
 * SHA-256 digest in the same pass. Any failure deletes the partial file.
 */
class ImageStager(
    private val context: Context,
    private val log: LogRepository,
    private val inspector: ImageInspector = ImageInspector(),
    private val hasher: FileHasher = FileHasher(log),
) {
    private val stagingDir: File get() = File(context.cacheDir, STAGING_DIR_NAME)

    /**
     * Stages [uri]. [onProgress] receives a 0..1 fraction when the size is known,
     * or `-1f` when the provider does not expose a size (indeterminate).
     */
    suspend fun stage(uri: Uri, onProgress: (Float) -> Unit = {}): StagedImage = withContext(Dispatchers.IO) {
        cleanupStaleInternal()
        log.info(TAG, "开始暂存镜像：$uri")

        val displayName = displayName(uri)
        val declaredSize = querySize(uri)
        log.debug(TAG, "显示名称=$displayName，提供方声明大小=${declaredSize?.let { Format.bytes(it) } ?: "未知"}")

        val dir = stagingDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw ImageStageException("无法创建镜像暂存目录（${dir.path}），请检查设备存储空间。")
        }
        if (declaredSize != null && declaredSize > 0L) {
            val required = declaredSize + FREE_SPACE_MARGIN_BYTES
            val usable = dir.usableSpace
            if (usable in 1 until required) {
                throw ImageStageException(
                    "设备存储空间不足：需要约 ${Format.bytes(required)}，当前可用 ${Format.bytes(usable)}。" +
                        "请清理缓存或选择更小的镜像后重试。",
                )
            }
        }

        val target = uniqueFile(dir, safeStagingName(displayName, System.currentTimeMillis()))
        var written = 0L
        val digest = hasher.newSha256Digest()
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw ImageStageException("无法打开所选文件，请重新选择镜像文件。")
            inputStream.use { input ->
                BufferedInputStream(input, BUFFER_SIZE).use { buffered ->
                    FileOutputStream(target).buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastProgress = -1f
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = buffered.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            val progress = if (declaredSize != null && declaredSize > 0L) {
                                (written.toDouble() / declaredSize.toDouble()).coerceIn(0.0, 1.0).toFloat()
                            } else {
                                -1f
                            }
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            if (written <= 0L) {
                throw ImageStageException("所选文件为空（0 字节），请重新选择有效的镜像文件。")
            }
            onProgress(1f)
            if (declaredSize != null && declaredSize > 0L && declaredSize != written) {
                log.warn(TAG, "文件实际大小（${Format.bytes(written)}）与提供方声明（${Format.bytes(declaredSize)}）不一致，以实际大小为准。")
            }
            val onePassSha256 = FileHasher.toHex(digest.digest())
            val sha256 = verifyStagedHash(target, onePassSha256, written)
            val metadata = try {
                inspector.inspect(target)
            } catch (e: Exception) {
                throw ImageStageException("无法读取镜像文件头，文件可能已损坏。", e)
            }
            log.success(
                TAG,
                "镜像暂存完成：$displayName（${Format.bytes(written)}，SHA-256=$sha256，" +
                    "格式=${metadata.formatLabel}，哈希后端=${FileHasher.backendName()}）",
            )
            StagedImage(
                displayName = displayName,
                sourceUri = uri.toString(),
                stagedPath = target.absolutePath,
                sizeBytes = written,
                sha256 = sha256,
                metadata = metadata,
                stagedAtMillis = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            deleteQuietly(target)
            throw e
        } catch (e: ImageStageException) {
            deleteQuietly(target)
            throw e
        } catch (e: SecurityException) {
            deleteQuietly(target)
            throw ImageStageException("没有访问该文件的权限，请重新选择镜像文件。", e)
        } catch (e: FileNotFoundException) {
            deleteQuietly(target)
            throw ImageStageException("无法读取所选文件，文件可能已被移动或删除，请重新选择。", e)
        } catch (e: IOException) {
            deleteQuietly(target)
            log.error(TAG, "暂存镜像 IO 失败：${e.message}")
            throw ImageStageException(
                "读取镜像文件失败：${e.message ?: "IO 错误"}。请检查设备存储空间后重试。",
                e,
            )
        } catch (e: Exception) {
            deleteQuietly(target)
            log.error(TAG, "暂存镜像失败：${e.message}")
            throw ImageStageException("暂存镜像失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Second-pass hash verification. For staged images up to
     * [MAX_SECOND_PASS_HASH_BYTES] the file is hashed again through
     * [FileHasher] — which uses the native JNI implementation when available and
     * falls back to MessageDigest otherwise — and compared with the digest that
     * was computed while copying. This both validates the staged file and makes
     * the native hasher a real runtime dependency (otherwise R8 would strip it).
     *
     * @return the authoritative SHA-256 for the staged image.
     */
    private fun verifyStagedHash(target: File, onePassSha256: String, sizeBytes: Long): String {
        if (!shouldVerifyStagedHash(sizeBytes)) {
            log.debug(
                TAG,
                "镜像大小 ${Format.bytes(sizeBytes)} 超过二次哈希上限 " +
                    "${Format.bytes(MAX_SECOND_PASS_HASH_BYTES)}，跳过二次校验（保留写入时计算的 SHA-256）。",
            )
            return onePassSha256
        }
        val recomputed = try {
            hasher.sha256(target)
        } catch (e: Exception) {
            log.warn(TAG, "二次哈希失败，保留写入时计算的 SHA-256：${e.message}")
            return onePassSha256
        }
        if (!isSameSha256(onePassSha256, recomputed)) {
            log.error(TAG, "暂存文件 SHA-256 二次校验失败：写入时=$onePassSha256，复算=$recomputed")
            throw ImageStageException(
                "暂存文件 SHA-256 与写入时不一致（写入时=$onePassSha256，复算=$recomputed），" +
                    "文件可能已损坏，请重新选择镜像。",
            )
        }
        log.debug(
            TAG,
            "二次校验通过：SHA-256 一致，哈希后端=${FileHasher.backendName()}（${Format.bytes(sizeBytes)}）",
        )
        return recomputed
    }

    /** Deletes staging files older than [maxAgeMillis]. */
    suspend fun cleanupStale(maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        withContext(Dispatchers.IO) { cleanupStaleInternal(maxAgeMillis) }
    }

    /** Best-effort display name for a content URI. */
    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val fromPath = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return fromPath ?: "image.img"
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun cleanupStaleInternal(maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        runCatching {
            val dir = stagingDir
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    if (deleteQuietly(file)) {
                        log.debug(TAG, "已清理过期暂存文件：${file.name}")
                    }
                }
            }
        }
    }

    private fun uniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, baseName)
        if (!candidate.exists()) return candidate
        val dot = baseName.lastIndexOf('.')
        val base = if (dot > 0) baseName.substring(0, dot) else baseName
        val extension = if (dot > 0) baseName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$counter$extension")
            counter++
            if (counter > 1000) {
                candidate = File(dir, "${base}_${System.nanoTime()}$extension")
                break
            }
        }
        return candidate
    }

    private fun deleteQuietly(file: File): Boolean = runCatching {
        if (file.exists()) file.delete() else true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ImageStager"
        private const val STAGING_DIR_NAME = "staging"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024
        private const val MAX_SAFE_NAME_LENGTH = 80

        /** Images up to this size get a second hashing pass (native when available). */
        const val MAX_SECOND_PASS_HASH_BYTES = 256L * 1024 * 1024

        /** True when a staged image is small enough to be hashed a second time. */
        fun shouldVerifyStagedHash(sizeBytes: Long): Boolean =
            sizeBytes in 1..MAX_SECOND_PASS_HASH_BYTES

        /** Case-insensitive SHA-256 comparison (native may return uppercase). */
        internal fun isSameSha256(expected: String, actual: String): Boolean =
            expected.length == actual.length && expected.equals(actual, ignoreCase = true)

        /**
         * Builds a safe file name for the staging directory. Only ASCII letters,
         * digits, `.`, `-` and `_` are kept; everything else becomes `_`. A
         * timestamp prefix makes the name unique and never shell-dangerous.
         */
        fun safeStagingName(displayName: String, timestampMillis: Long): String {
            val raw = displayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            val safe = buildString {
                for (ch in raw) {
                    val allowed = (ch in 'a'..'z') || (ch in 'A'..'Z') ||
                        (ch in '0'..'9') || ch == '.' || ch == '-' || ch == '_'
                    append(if (allowed) ch else '_')
                }
            }.trim('.', '_', '-').take(MAX_SAFE_NAME_LENGTH)
            val base = safe.ifBlank { "image.img" }
            val withExtension = if (base.contains('.')) base else "$base.img"
            return "img_${timestampMillis}_$withExtension"
        }
    }
}

class ImageStageException(message: String, cause: Throwable? = null) : Exception(message, cause)
