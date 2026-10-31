package com.partflasher.app.core.flash

import com.partflasher.app.core.TestShellResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashScriptBuilderTest {

    private val mib = 1024L * 1024L

    // ------------------------------------------------------------------ path safety

    @Test
    fun remoteImagePathSanitizesUntrustedNames() {
        val path = FlashScriptBuilder.remoteImagePath("../../evil name'; rm -rf / #.img", 123L)
        assertTrue(path, path.startsWith("${FlashScriptBuilder.REMOTE_DIR}/flash_123_"))
        assertFalse(path, path.contains("'"))
        assertFalse(path, path.contains(";"))
        assertFalse(path, path.contains(" "))
        assertFalse(path, path.contains(".."))
    }

    @Test
    fun backupPathSanitizesPartitionName() {
        val path = FlashScriptBuilder.backupPath("boot_a", 456L)
        assertEquals("${FlashScriptBuilder.REMOTE_DIR}/backup_boot_a_456.img", path)
        val malicious = FlashScriptBuilder.backupPath("a/b';reboot", 1L)
        assertFalse(malicious.contains("'"))
        assertFalse(malicious.contains(";"))
        assertFalse(malicious.substringAfter("backup_").contains('/'))
    }

    // ------------------------------------------------------------------ chunking

    @Test
    fun flashScriptUsesFourMiBChunksAndProgressLines() {
        val total = FlashScriptBuilder.CHUNK_BYTES * 2 + 1000L
        val script = FlashScriptBuilder.flashScript(
            remoteImagePath = "/data/local/tmp/partflasher/flash_1_boot.img",
            partitionPath = "/dev/block/by-name/boot_a",
            totalBytes = total,
        )
        assertTrue(script.contains("TOTAL=$total"))
        assertTrue(script.contains("CHUNK=${FlashScriptBuilder.CHUNK_BYTES}"))
        assertTrue(script.contains("bs=\$CHUNK skip=\$i seek=\$i count=1 conv=notrunc"))
        assertTrue(script.contains("PROGRESS:\$off:\$TOTAL"))
        assertTrue(script.contains("sync"))
        assertTrue(script.contains("FLASH_DONE:\$TOTAL"))
        // Tail path (not 4 MiB aligned) must be present and use 512-byte blocks
        // plus a small byte-wise remainder for non-512-aligned sizes.
        assertTrue(script.contains("aligned=\$((n / ${FlashScriptBuilder.TAIL_ALIGN}))"))
        assertTrue(script.contains("small=\$((n % ${FlashScriptBuilder.TAIL_ALIGN}))"))
        assertTrue(script.contains("bs=${FlashScriptBuilder.TAIL_ALIGN}"))
        assertTrue(script.contains("bs=1"))
    }

    @Test
    fun flashScriptHandlesImageSmallerThanOneChunk() {
        val total = 300_000L
        val script = FlashScriptBuilder.flashScript("/tmp/flash_1.img", "/dev/block/sda1", total)
        assertTrue(script.contains("TOTAL=$total"))
        // 300000 = 585 * 512 + 480 -> tail uses 512-byte blocks + bs=1 remainder
        assertTrue(script.contains("bs=${FlashScriptBuilder.TAIL_ALIGN}"))
        assertTrue(script.contains("bs=1"))
    }

    @Test
    fun flashScriptExactlyOneChunk() {
        val total = FlashScriptBuilder.CHUNK_BYTES
        val script = FlashScriptBuilder.flashScript("/tmp/flash_1.img", "/dev/block/sda1", total)
        assertTrue(script.contains("TOTAL=$total"))
        assertTrue(script.contains("while [ \$off -lt \$TOTAL ]"))
    }

    @Test
    fun flashScriptQuotesMaliciousPaths() {
        val maliciousPartition = "/dev/block/by-name/boot'; reboot; echo '"
        val script = FlashScriptBuilder.flashScript(
            remoteImagePath = "/data/local/tmp/x'; reboot #",
            partitionPath = maliciousPartition,
            totalBytes = 4096L,
        )
        // The malicious partition path must appear in escaped (single-quote) form.
        assertTrue(script.contains("DST=${com.partflasher.app.core.root.ShellScriptBuilder.quote(maliciousPartition)}"))
        assertTrue(script.contains("'\\''"))
    }

    @Test
    fun backupScriptReadsChunksAndVerifiesSize() {
        val script = FlashScriptBuilder.backupScript(
            partitionPath = "/dev/block/by-name/boot_a",
            backupPath = "/data/local/tmp/partflasher/backup_boot_a_1.img",
            totalBytes = 64L * mib,
        )
        assertTrue(script.contains("TOTAL=${64L * mib}"))
        assertTrue(script.contains("if [ ! -b \"\$PART\" ]"))
        assertTrue(script.contains("PROGRESS:\$off:\$TOTAL"))
        assertTrue(script.contains("BACKUP_SIZE:\$size"))
        assertTrue(script.contains("conv=notrunc"))
    }

    @Test
    fun copyScriptVerifiesCopiedSize() {
        val script = FlashScriptBuilder.copyToRootScript(
            localPath = "/data/user/0/app/cache/staging/img_boot.img",
            remotePath = "/data/local/tmp/partflasher/flash_1_boot.img",
            expectedBytes = 4096L,
        )
        assertTrue(script.contains("mkdir -p"))
        assertTrue(script.contains("cp "))
        assertTrue(script.contains("COPIED:\$size"))
        assertTrue(script.contains("!= \"4096\""))
        assertTrue(script.contains("COPY_ERROR"))
    }

    @Test
    fun freeSpaceScriptAndParserWork() {
        val script = FlashScriptBuilder.checkFreeSpaceScript()
        assertTrue(script.contains("df -Pk ${FlashScriptBuilder.REMOTE_DIR_PARENT}"))
        assertEquals(1048576L, FlashScriptBuilder.parseFreeSpaceKb("DF:1048576\n"))
        assertNull(FlashScriptBuilder.parseFreeSpaceKb("DF:UNKNOWN\n"))
        assertNull(FlashScriptBuilder.parseFreeSpaceKb("nothing"))
    }

    @Test
    fun verifyScriptComparesSha256() {
        val script = FlashScriptBuilder.verifyScript(
            partitionPath = "/dev/block/by-name/boot_a",
            sizeBytes = 4096L,
            expectedSha256 = "ABCDEF".padEnd(64, '0'),
        )
        assertTrue(script.contains("head -c \$TOTAL"))
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains("SHA256:\$got"))
        assertTrue(script.contains("VERIFY_MISMATCH"))
        assertTrue(script.contains("VERIFY_OK"))
        assertTrue(script.contains("abcdef".padEnd(64, '0')))
    }

    @Test
    fun cleanupScriptQuotesRemotePath() {
        val script = FlashScriptBuilder.cleanupScript("/data/local/tmp/x';reboot")
        assertTrue(script.contains("rm -f \"\$REMOTE\""))
        assertTrue(script.contains("'\\''"))
    }

    // ------------------------------------------------------------------ parsing

    @Test
    fun parsesValidProgressLines() {
        val progress = FlashScriptBuilder.parseProgressLine("PROGRESS:1048576:67108864")!!
        assertEquals(1_048_576L, progress.written)
        assertEquals(67_108_864L, progress.total)
        assertEquals(1f / 64f, progress.fraction, 0.0001f)

        val done = FlashScriptBuilder.parseProgressLine("PROGRESS:67108864:67108864")!!
        assertEquals(1f, done.fraction, 0f)
    }

    @Test
    fun clampsProgressWrittenBeyondTotal() {
        val progress = FlashScriptBuilder.parseProgressLine("PROGRESS:9999:1000")!!
        assertEquals(1000L, progress.written)
    }

    @Test
    fun ignoresMalformedProgressLines() {
        val malformed = listOf(
            "",
            "hello",
            "PROGRESS:",
            "PROGRESS:1",
            "PROGRESS:1:2:3",
            "PROGRESS:abc:100",
            "PROGRESS:100:abc",
            "PROGRESS:-1:100",
            "PROGRESS:100:0",
            "PROGRESS:100:-1",
            "progress:100:200",
            "PROGRESS: : ",
        )
        for (line in malformed) {
            assertNull("line should be ignored: '$line'", FlashScriptBuilder.parseProgressLine(line))
        }
    }

    @Test
    fun parsesCopiedBackupAndShaOutputs() {
        assertEquals(4096L, FlashScriptBuilder.parseCopiedBytes("noise\nCOPIED:4096\n"))
        assertEquals(1234L, FlashScriptBuilder.parseBackupBytes("PROGRESS:1:2\nBACKUP_SIZE:1234\n"))
        assertNull(FlashScriptBuilder.parseCopiedBytes("COPY_ERROR:cp\n"))
        val sha = "A".repeat(64)
        assertEquals(sha.lowercase(), FlashScriptBuilder.parseSha256("SHA256:$sha\n"))
        assertNull(FlashScriptBuilder.parseSha256("SHA256:nothex"))
        assertNull(FlashScriptBuilder.parseSha256(""))
    }

    @Test
    fun backupLimitMatchesSpec() {
        assertEquals(512L * 1024L * 1024L, FlashScriptBuilder.MAX_BACKUP_BYTES)
        assertEquals(4L * 1024L * 1024L, FlashScriptBuilder.CHUNK_BYTES)
        assertEquals("/data/local/tmp/partflasher", FlashScriptBuilder.REMOTE_DIR)
    }

    @Test
    fun verifyScriptChecksForSha256sumAndEmitsUnsupported() {
        val script = FlashScriptBuilder.verifyScript(
            partitionPath = "/dev/block/by-name/boot_a",
            sizeBytes = 4096L,
            expectedSha256 = "a".repeat(64),
        )
        assertTrue(script.contains("command -v sha256sum"))
        assertTrue(script.contains("VERIFY_UNSUPPORTED"))
        assertTrue(script.contains("exit ${FlashScriptBuilder.VERIFY_EXIT_UNSUPPORTED}"))
        assertEquals(3, FlashScriptBuilder.VERIFY_EXIT_UNSUPPORTED)
    }

    @Test
    fun isVerifyUnsupportedOnlyMatchesTheDedicatedExitMarker() {
        val unsupported = TestShellResults.failure(
            stdout = "VERIFY_UNSUPPORTED\n",
            exitCode = FlashScriptBuilder.VERIFY_EXIT_UNSUPPORTED,
        )
        assertTrue(FlashScriptBuilder.isVerifyUnsupported(unsupported))

        val plainFailure = TestShellResults.failure(
            stdout = "VERIFY_UNSUPPORTED\n",
            exitCode = 1,
        )
        assertFalse(FlashScriptBuilder.isVerifyUnsupported(plainFailure))

        val exitThreeWithoutMarker = TestShellResults.failure(stderr = "boom", exitCode = 3)
        assertFalse(FlashScriptBuilder.isVerifyUnsupported(exitThreeWithoutMarker))

        val mismatch = TestShellResults.failure(
            stdout = "VERIFY_MISMATCH:${"b".repeat(64)}\n",
            exitCode = 1,
        )
        assertFalse(FlashScriptBuilder.isVerifyUnsupported(mismatch))
        assertFalse(FlashScriptBuilder.isVerifyUnsupported(TestShellResults.ok("VERIFY_OK\n")))
    }

}
