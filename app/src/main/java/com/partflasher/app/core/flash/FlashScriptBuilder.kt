package com.partflasher.app.core.flash

import com.partflasher.app.core.root.ShellResult
import com.partflasher.app.core.root.ShellScriptBuilder

/**
 * Pure builders/parsers for the root shell scripts used while flashing. Kept
 * separately from [FlashController] so script safety and output parsing can be
 * unit tested without an Android device.
 *
 * Every path is quoted with [ShellScriptBuilder.quote] before interpolation;
 * only compile-time constants and decimal numbers are embedded unquoted.
 */
object FlashScriptBuilder {

    /** 4 MiB write chunks, per spec. */
    const val CHUNK_BYTES = 4L * 1024L * 1024L

    /** Offset alignment used for the final partial chunk. */
    const val TAIL_ALIGN = 512L

    /** Backups larger than this are refused before any write happens. */
    const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L

    /** [verifyScript] exit code when the device has no `sha256sum` binary. */
    const val VERIFY_EXIT_UNSUPPORTED = 3

    /** Root-owned working directory on the device. */
    const val REMOTE_DIR = "/data/local/tmp/partflasher"

    /** Filesystem mount that hosts [REMOTE_DIR], used for the free-space check. */
    const val REMOTE_DIR_PARENT = "/data/local/tmp"

    private val SAFE_TOKEN_CHARS = ('a'..'z').toSet() +
        ('A'..'Z').toSet() +
        ('0'..'9').toSet() +
        setOf('.', '-', '_')

    /** Restricts a name to a shell-safe token (never contains `/` or quotes). */
    fun sanitizeToken(value: String, fallback: String): String {
        val cleaned = buildString {
            for (ch in value) append(if (ch in SAFE_TOKEN_CHARS) ch else '_')
        }.trim('.', '_', '-').take(64)
        return cleaned.ifBlank { fallback }
    }

    fun remoteImagePath(stagedFileName: String, timestampMillis: Long): String {
        val safeName = sanitizeToken(stagedFileName, "image.img")
        return "$REMOTE_DIR/flash_${timestampMillis}_$safeName"
    }

    fun backupPath(partitionName: String, timestampMillis: Long): String {
        val safeName = sanitizeToken(partitionName, "partition")
        return "$REMOTE_DIR/backup_${safeName}_$timestampMillis.img"
    }

    /** Creates the root working directory and copies the staged image; verifies the copied size. */
    fun copyToRootScript(localPath: String, remotePath: String, expectedBytes: Long): String {
        require(expectedBytes > 0L) { "expectedBytes 必须大于 0" }
        val dir = ShellScriptBuilder.quote(REMOTE_DIR)
        val local = ShellScriptBuilder.quote(localPath)
        val remote = ShellScriptBuilder.quote(remotePath)
        return ShellScriptBuilder.build(
            "DIR=$dir",
            "mkdir -p \"\$DIR\" || { echo 'COPY_ERROR:mkdir'; exit 1; }",
            "LOCAL=$local",
            "REMOTE=$remote",
            "rm -f \"\$REMOTE\" 2>/dev/null",
            "cp \"\$LOCAL\" \"\$REMOTE\" || { echo 'COPY_ERROR:cp'; exit 1; }",
            "sync",
            "size=\$(wc -c < \"\$REMOTE\" 2>/dev/null | tr -d ' ')",
            "case \"\$size\" in ''|*[!0-9]*) size=0 ;; esac",
            "echo \"COPIED:\$size\"",
            "if [ \"\$size\" != \"$expectedBytes\" ]; then echo \"COPY_ERROR:size:\$size\"; exit 1; fi",
        )
    }

    /** Emits `DF:<available KiB>` for the filesystem hosting [REMOTE_DIR]. */
    fun checkFreeSpaceScript(): String = ShellScriptBuilder.build(
        "avail=\$(df -Pk $REMOTE_DIR_PARENT 2>/dev/null | tail -n 1 | awk '{print \$4}' 2>/dev/null)",
        "case \"\$avail\" in ''|*[!0-9]*) echo 'DF:UNKNOWN' ;; *) echo \"DF:\$avail\" ;; esac",
    )

    /**
     * Reads the whole partition [partitionPath] into [backupPath] in 4 MiB
     * chunks, emitting `PROGRESS:<written>:<total>` and verifying the final size.
     */
    fun backupScript(partitionPath: String, backupPath: String, totalBytes: Long): String {
        require(totalBytes > 0L) { "totalBytes 必须大于 0" }
        val part = ShellScriptBuilder.quote(partitionPath)
        val out = ShellScriptBuilder.quote(backupPath)
        return ShellScriptBuilder.build(
            "PART=$part",
            "OUT=$out",
            "TOTAL=$totalBytes",
            "CHUNK=$CHUNK_BYTES",
            "if [ ! -b \"\$PART\" ]; then echo 'BACKUP_ERROR:not_block'; exit 1; fi",
            "rm -f \"\$OUT\" 2>/dev/null",
            ": > \"\$OUT\" 2>/dev/null || { echo 'BACKUP_ERROR:create'; exit 1; }",
            "off=0",
            "i=0",
            "while [ \$off -lt \$TOTAL ]; do",
            "  n=\$CHUNK",
            "  rem=\$((TOTAL - off))",
            "  if [ \$rem -lt \$n ]; then n=\$rem; fi",
            "  if [ \$n -eq \$CHUNK ]; then",
            "    dd if=\"\$PART\" of=\"\$OUT\" bs=\$CHUNK skip=\$i seek=\$i count=1 conv=notrunc 2>/dev/null || { echo \"BACKUP_ERROR:dd:\$off\"; exit 1; }",
            "  else",
            "    aligned=\$((n / $TAIL_ALIGN))",
            "    small=\$((n % $TAIL_ALIGN))",
            "    if [ \$aligned -gt 0 ]; then",
            "      dd if=\"\$PART\" of=\"\$OUT\" bs=$TAIL_ALIGN skip=\$((off / $TAIL_ALIGN)) seek=\$((off / $TAIL_ALIGN)) count=\$aligned conv=notrunc 2>/dev/null || { echo \"BACKUP_ERROR:dd_tail:\$off\"; exit 1; }",
            "    fi",
            "    if [ \$small -gt 0 ]; then",
            "      tail_off=\$((off + aligned * $TAIL_ALIGN))",
            "      dd if=\"\$PART\" of=\"\$OUT\" bs=1 skip=\$tail_off seek=\$tail_off count=\$small conv=notrunc 2>/dev/null || { echo \"BACKUP_ERROR:dd_tail:\$off\"; exit 1; }",
            "    fi",
            "  fi",
            "  off=\$((off + n))",
            "  i=\$((i + 1))",
            "  echo \"PROGRESS:\$off:\$TOTAL\"",
            "done",
            "sync",
            "size=\$(wc -c < \"\$OUT\" 2>/dev/null | tr -d ' ')",
            "case \"\$size\" in ''|*[!0-9]*) size=0 ;; esac",
            "echo \"BACKUP_SIZE:\$size\"",
            "if [ \"\$size\" != \"\$TOTAL\" ]; then echo \"BACKUP_ERROR:size:\$size\"; exit 1; fi",
        )
    }

    /**
     * Writes the root-staged image to [partitionPath] in 4 MiB `dd` chunks with
     * `conv=notrunc`, emitting `PROGRESS:<written>:<total>` after each chunk,
     * then syncs.
     */
    fun flashScript(remoteImagePath: String, partitionPath: String, totalBytes: Long): String {
        require(totalBytes > 0L) { "totalBytes 必须大于 0" }
        val src = ShellScriptBuilder.quote(remoteImagePath)
        val dst = ShellScriptBuilder.quote(partitionPath)
        return ShellScriptBuilder.build(
            "SRC=$src",
            "DST=$dst",
            "TOTAL=$totalBytes",
            "CHUNK=$CHUNK_BYTES",
            "if [ ! -b \"\$DST\" ]; then echo 'FLASH_ERROR:not_block'; exit 1; fi",
            "if [ ! -f \"\$SRC\" ]; then echo 'FLASH_ERROR:source_missing'; exit 1; fi",
            "src_size=\$(wc -c < \"\$SRC\" 2>/dev/null | tr -d ' ')",
            "case \"\$src_size\" in ''|*[!0-9]*) src_size=0 ;; esac",
            "if [ \"\$src_size\" != \"\$TOTAL\" ]; then echo \"FLASH_ERROR:source_size:\$src_size\"; exit 1; fi",
            "off=0",
            "i=0",
            "while [ \$off -lt \$TOTAL ]; do",
            "  n=\$CHUNK",
            "  rem=\$((TOTAL - off))",
            "  if [ \$rem -lt \$n ]; then n=\$rem; fi",
            "  if [ \$n -eq \$CHUNK ]; then",
            "    dd if=\"\$SRC\" of=\"\$DST\" bs=\$CHUNK skip=\$i seek=\$i count=1 conv=notrunc 2>/dev/null || { echo \"FLASH_ERROR:dd:\$off\"; exit 1; }",
            "  else",
            "    aligned=\$((n / $TAIL_ALIGN))",
            "    small=\$((n % $TAIL_ALIGN))",
            "    if [ \$aligned -gt 0 ]; then",
            "      dd if=\"\$SRC\" of=\"\$DST\" bs=$TAIL_ALIGN skip=\$((off / $TAIL_ALIGN)) seek=\$((off / $TAIL_ALIGN)) count=\$aligned conv=notrunc 2>/dev/null || { echo \"FLASH_ERROR:dd_tail:\$off\"; exit 1; }",
            "    fi",
            "    if [ \$small -gt 0 ]; then",
            "      tail_off=\$((off + aligned * $TAIL_ALIGN))",
            "      dd if=\"\$SRC\" of=\"\$DST\" bs=1 skip=\$tail_off seek=\$tail_off count=\$small conv=notrunc 2>/dev/null || { echo \"FLASH_ERROR:dd_tail:\$off\"; exit 1; }",
            "    fi",
            "  fi",
            "  off=\$((off + n))",
            "  i=\$((i + 1))",
            "  echo \"PROGRESS:\$off:\$TOTAL\"",
            "done",
            "sync",
            "echo \"FLASH_DONE:\$TOTAL\"",
        )
    }

    fun syncScript(): String = "sync"

    /** Reads back [sizeBytes] from the partition and compares its SHA-256. */
    fun verifyScript(partitionPath: String, sizeBytes: Long, expectedSha256: String): String {
        require(sizeBytes > 0L) { "sizeBytes 必须大于 0" }
        val part = ShellScriptBuilder.quote(partitionPath)
        val expected = ShellScriptBuilder.quote(expectedSha256.lowercase())
        return ShellScriptBuilder.build(
            "PART=$part",
            "TOTAL=$sizeBytes",
            "EXPECTED=$expected",
            "if ! command -v sha256sum >/dev/null 2>&1; then echo 'VERIFY_UNSUPPORTED'; exit $VERIFY_EXIT_UNSUPPORTED; fi",
            "got=\$(head -c \$TOTAL \"\$PART\" 2>/dev/null | sha256sum 2>/dev/null | awk '{print \$1}')",
            "if [ -z \"\$got\" ]; then echo 'VERIFY_ERROR:no_sha256sum'; exit 1; fi",
            "echo \"SHA256:\$got\"",
            "if [ \"\$got\" != \"\$EXPECTED\" ]; then echo \"VERIFY_MISMATCH:\$got\"; exit 1; fi",
            "echo 'VERIFY_OK'",
        )
    }

    /** Removes the root-staged image, keeping backups untouched. */
    fun cleanupScript(remoteImagePath: String): String = ShellScriptBuilder.build(
        "REMOTE=${ShellScriptBuilder.quote(remoteImagePath)}",
        "rm -f \"\$REMOTE\" 2>/dev/null",
        "echo 'CLEANED'",
    )

    // ------------------------------------------------------------------ parsers

    data class FlashProgress(val written: Long, val total: Long) {
        val fraction: Float
            get() = if (total <= 0L) 0f else (written.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** Parses `PROGRESS:<written>:<total>`; malformed lines are ignored (null). */
    fun parseProgressLine(line: String): FlashProgress? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("PROGRESS:")) return null
        val parts = trimmed.split(':')
        if (parts.size != 3) return null
        val written = parts[1].trim().toLongOrNull() ?: return null
        val total = parts[2].trim().toLongOrNull() ?: return null
        if (written < 0L || total <= 0L) return null
        return FlashProgress(written.coerceAtMost(total), total)
    }

    /** Parses `COPIED:<bytes>`. */
    fun parseCopiedBytes(output: String): Long? = parseLongAfter(output, "COPIED:")

    /** Parses `BACKUP_SIZE:<bytes>`. */
    fun parseBackupBytes(output: String): Long? = parseLongAfter(output, "BACKUP_SIZE:")

    /** Parses `DF:<available KiB>`. */
    fun parseFreeSpaceKb(output: String): Long? = parseLongAfter(output, "DF:")

    /**
     * True when post-write verification was skipped because the device has no
     * `sha256sum` binary. The write itself succeeded, so the controller must not
     * report a failure; verification is reported as skipped (verified = null).
     */
    fun isVerifyUnsupported(result: ShellResult): Boolean =
        result.exitCode == VERIFY_EXIT_UNSUPPORTED &&
            result.stdout.lineSequence().any { it.trim() == "VERIFY_UNSUPPORTED" }

    /** Parses `SHA256:<hex>`. */
    fun parseSha256(output: String): String? {
        val match = Regex("""SHA256:([0-9a-fA-F]{64})""").find(output) ?: return null
        return match.groupValues[1].lowercase()
    }

    private fun parseLongAfter(output: String, prefix: String): Long? {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(prefix)) continue
            return trimmed.removePrefix(prefix).trim().toLongOrNull()
        }
        return null
    }
}
