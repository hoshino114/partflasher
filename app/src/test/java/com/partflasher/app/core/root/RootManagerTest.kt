package com.partflasher.app.core.root

import com.partflasher.app.core.FakeCommandRunner
import com.partflasher.app.core.TestShellResults
import com.partflasher.app.core.model.RootSolution
import com.partflasher.app.core.model.RootState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootManagerTest {

    private val probeFound = TestShellResults.ok("/system/bin/su\n")
    private val idGranted = TestShellResults.ok("uid=0(root) gid=0(root) groups=0(root)\n")

    private val magiskOutput = """
        SU_VERSION_BEGIN
        27.0:MAGISK
        SU_VERSION_END
        MANAGER_BEGIN
        DIR:/data/adb/magisk
        DIR:/data/adb/modules
        MAGISK_VERSION:27.0
        MANAGER_END
    """.trimIndent()

    private val kernelSuOutput = """
        SU_VERSION_BEGIN
        KernelSU_v0.9.5
        SU_VERSION_END
        MANAGER_BEGIN
        DIR:/data/adb/ksu
        BIN:ksud:/data/adb/ksu/bin/ksud
        KSU_VERSION:0.9.5
        MANAGER_END
    """.trimIndent()

    private val apatchOutput = """
        SU_VERSION_BEGIN
        APatch_0.10.7
        SU_VERSION_END
        MANAGER_BEGIN
        DIR:/data/adb/ap
        APATCH_VERSION:0.10.7
        MANAGER_END
    """.trimIndent()

    private fun runner(
        probe: ShellResult = probeFound,
        id: ShellResult = idGranted,
        manager: ShellResult = TestShellResults.ok(magiskOutput),
    ): FakeCommandRunner = FakeCommandRunner { command, _, asRoot ->
        when {
            command == RootProbeScripts.PROBE_SU && !asRoot -> probe
            command == "id" && asRoot -> id
            command == RootProbeScripts.MANAGEINFO && asRoot -> manager
            else -> TestShellResults.failure("unexpected command: $command")
        }
    }

    // ------------------------------------------------------------------ detection

    @Test
    fun noSuMeansNotRooted() = runBlocking {
        val manager = RootManager(runner(probe = TestShellResults.ok("")))
        val status = manager.detect(request = true)
        assertEquals(RootState.NOT_ROOTED, status.state)
        assertEquals(RootSolution.NONE, status.solution)
        assertTrue(status.message.contains("未检测到 Root"))
        assertFalse(status.isGranted)
        // no su -> the su authorization command must not be attempted
        assertTrue(manager.cachedStatus == status)
    }

    @Test
    fun requestFalseNeverExecutesSu() = runBlocking {
        val fake = runner()
        val manager = RootManager(fake)
        val status = manager.detect(request = false)
        assertEquals(RootState.UNKNOWN, status.state)
        assertTrue(status.message.contains("尚未获得 Root 授权"))
        assertTrue("no root command may run without an explicit request", fake.rootCalls.isEmpty())
        assertNotNull(status.suPath)
    }

    @Test
    fun detectsMagiskAndParsesVersion() = runBlocking {
        val manager = RootManager(runner(manager = TestShellResults.ok(magiskOutput)))
        val status = manager.detect(request = true)
        assertEquals(RootState.GRANTED, status.state)
        assertEquals(RootSolution.MAGISK, status.solution)
        assertEquals(0, status.uid)
        assertTrue("version was ${status.version}", status.version.orEmpty().contains("27.0"))
        assertEquals("/system/bin/su", status.suPath)
        assertTrue(status.message.contains("已获取 Root 权限"))
        assertTrue(status.detail.orEmpty().contains("magisk"))
    }

    @Test
    fun detectsKernelSu() = runBlocking {
        val manager = RootManager(runner(manager = TestShellResults.ok(kernelSuOutput)))
        val status = manager.detect(request = true)
        assertEquals(RootState.GRANTED, status.state)
        assertEquals(RootSolution.KERNELSU, status.solution)
        assertTrue(status.version.orEmpty().contains("0.9.5"))
    }

    @Test
    fun detectsApatch() = runBlocking {
        val manager = RootManager(runner(manager = TestShellResults.ok(apatchOutput)))
        val status = manager.detect(request = true)
        assertEquals(RootState.GRANTED, status.state)
        assertEquals(RootSolution.APATCH, status.solution)
        assertTrue(status.version.orEmpty().contains("0.10.7"))
    }

    @Test
    fun deniedRequestProducesActionableDeniedState() = runBlocking {
        val manager = RootManager(
            runner(id = TestShellResults.failure(stderr = "Permission denied\n", exitCode = 1)),
        )
        val status = manager.detect(request = true)
        assertEquals(RootState.DENIED, status.state)
        assertTrue(status.message.contains("拒绝"))
        assertTrue(status.message.contains("Magisk") || status.message.contains("Root 管理器"))
        assertFalse(status.isGranted)
    }

    @Test
    fun timeoutIsReportedSeparatelyFromDenial() = runBlocking {
        val manager = RootManager(runner(id = TestShellResults.timeout()))
        val status = manager.detect(request = true)
        assertEquals(RootState.ERROR, status.state)
        assertTrue(status.message.contains("超时"))
    }

    @Test
    fun detectNeverThrowsOnUnexpectedFailure() = runBlocking {
        val fake = FakeCommandRunner { _, _, _ -> throw IllegalStateException("boom") }
        val manager = RootManager(fake)
        val status = manager.detect(request = true)
        assertEquals(RootState.ERROR, status.state)
        assertTrue(status.message.contains("boom"))
    }

    @Test
    fun unknownManagerIsClassifiedAsOther() = runBlocking {
        val manager = RootManager(
            runner(manager = TestShellResults.ok("SU_VERSION_BEGIN\nSU_VERSION_END\nMANAGER_BEGIN\nMANAGER_END")),
        )
        val status = manager.detect(request = true)
        assertEquals(RootState.GRANTED, status.state)
        assertEquals(RootSolution.OTHER, status.solution)
    }

    // ------------------------------------------------------------------ ensure/refresh

    @Test
    fun ensureRootThrowsWhenNotGranted() = runBlocking {
        val manager = RootManager(runner(id = TestShellResults.failure(stderr = "Permission denied\n")))
        manager.detect(request = true)
        val exception = assertThrows(RootAccessException::class.java) {
            runBlocking { manager.ensureRoot() }
        }
        assertEquals(RootState.DENIED, exception.status.state)
    }

    @Test
    fun ensureRootThrowsWhenNoDetectionHappened() = runBlocking {
        val manager = RootManager(runner(probe = TestShellResults.ok("")))
        val exception = assertThrows(RootAccessException::class.java) {
            runBlocking { manager.ensureRoot() }
        }
        assertEquals(RootState.NOT_ROOTED, exception.status.state)
    }

    @Test
    fun ensureRootVerifiesGrantedSession() = runBlocking {
        val manager = RootManager(runner())
        manager.detect(request = true)
        val status = manager.ensureRoot()
        assertEquals(RootState.GRANTED, status.state)
        assertEquals(0, status.uid)
    }

    @Test
    fun ensureRootDetectsRevokedPermission() = runBlocking {
        var granted = true
        val fake = FakeCommandRunner { command, _, asRoot ->
            when {
                command == RootProbeScripts.PROBE_SU && !asRoot -> probeFound
                command == "id" && asRoot && granted ->
                    TestShellResults.ok("uid=0(root) gid=0(root)\n")
                command == "id" && asRoot -> TestShellResults.failure("Permission denied")
                command == RootProbeScripts.MANAGEINFO && asRoot -> TestShellResults.ok(magiskOutput)
                else -> TestShellResults.failure("unexpected: $command")
            }
        }
        val manager = RootManager(fake)
        manager.detect(request = true)
        granted = false
        val exception = assertThrows(RootAccessException::class.java) {
            runBlocking { manager.ensureRoot() }
        }
        assertEquals(RootState.DENIED, exception.status.state)
        assertTrue(exception.message.orEmpty().contains("失效") || exception.message.orEmpty().contains("拒绝"))
    }

    @Test
    fun refreshWithoutGrantedCacheDoesNotRequestRoot() = runBlocking {
        val fake = runner()
        val manager = RootManager(fake)
        val status = manager.refresh()
        assertEquals(RootState.UNKNOWN, status.state)
        assertTrue(fake.rootCalls.isEmpty())
    }

    @Test
    fun refreshValidatesGrantedSession() = runBlocking {
        var granted = true
        val fake = FakeCommandRunner { command, _, asRoot ->
            when {
                command == RootProbeScripts.PROBE_SU && !asRoot -> probeFound
                command == "id" && asRoot && granted ->
                    TestShellResults.ok("uid=0(root) gid=0(root)\n")
                command == "id" && asRoot -> TestShellResults.failure("Permission denied")
                command == RootProbeScripts.MANAGEINFO && asRoot -> TestShellResults.ok(magiskOutput)
                else -> TestShellResults.failure("unexpected: $command")
            }
        }
        val manager = RootManager(fake)
        manager.detect(request = true)

        val stillGranted = manager.refresh()
        assertEquals(RootState.GRANTED, stillGranted.state)

        granted = false
        val revoked = manager.refresh()
        assertEquals(RootState.DENIED, revoked.state)
    }

    @Test
    fun rootDetectionParserParsesUidVariants() {
        assertEquals(0, RootDetectionParser.parseUid("uid=0(root) gid=0(root)", ""))
        assertEquals(0, RootDetectionParser.parseUid("0", ""))
        assertEquals(10123, RootDetectionParser.parseUid("uid=10123(u0_a123)", ""))
        assertEquals(null, RootDetectionParser.parseUid("Permission denied", "Permission denied"))
    }

    @Test
    fun rootDetectionParserClassifiesFailures() {
        assertEquals(RootState.ERROR, RootDetectionParser.classifyFailure(TestShellResults.timeout()))
        assertEquals(
            RootState.DENIED,
            RootDetectionParser.classifyFailure(TestShellResults.failure("Permission denied")),
        )
        assertEquals(
            RootState.NOT_ROOTED,
            RootDetectionParser.classifyFailure(TestShellResults.failure("su: not found")),
        )
    }

    @Test
    fun rootDetectionParserExtractsSuPaths() {
        val paths = RootDetectionParser.parseSuPaths(
            "/system/bin/su\n/system/bin/su\n/does/not/exist\nnot-a-path\n",
        )
        assertEquals(listOf("/system/bin/su", "/does/not/exist"), paths)
    }

    @Test
    fun managerParserPrefersMostSpecificSolution() {
        val output = """
            SU_VERSION_BEGIN
            version-from-su
            SU_VERSION_END
            MANAGER_BEGIN
            DIR:/data/adb/magisk
            DIR:/data/adb/ksu
            KSU_VERSION:1.0.2
            MANAGER_END
        """.trimIndent()
        val info = RootDetectionParser.parseManager(output, listOf("/system/bin/su"))
        assertEquals(RootSolution.KERNELSU, info.solution)
        assertEquals("1.0.2", info.version)
    }

    @Test
    fun solutionLabelsAreHumanReadable() {
        assertEquals("Magisk", RootDetectionParser.solutionLabel(RootSolution.MAGISK))
        assertEquals("KernelSU", RootDetectionParser.solutionLabel(RootSolution.KERNELSU))
        assertEquals("APatch", RootDetectionParser.solutionLabel(RootSolution.APATCH))
    }

    @Test
    fun combinedSuVersionOutputsNormalizeToSingleCleanVersion() {
        // Real devices can print two different version formats for `su -v` and
        // `su --version`; the UI must not show "27.0:MAGISK MAGISK 27.0".
        val magisk = """
            SU_VERSION_BEGIN
            27.0:MAGISK
            MAGISK 27.0
            SU_VERSION_END
            MANAGER_BEGIN
            DIR:/data/adb/magisk
            MANAGER_END
        """.trimIndent()
        val info = RootDetectionParser.parseManager(magisk, listOf("/system/bin/su"))
        assertEquals(RootSolution.MAGISK, info.solution)
        assertEquals("27.0", info.version)
        assertFalse(info.version.orEmpty().contains("MAGISK"))
        assertFalse(info.version.orEmpty().contains(" "))
    }

    @Test
    fun managerVersionOutputsAreNormalizedForKernelSuAndApatch() {
        val kernelSu = """
            SU_VERSION_BEGIN
            KernelSU_v0.9.5
            0.9.5
            SU_VERSION_END
            MANAGER_BEGIN
            DIR:/data/adb/ksu
            MANAGER_END
        """.trimIndent()
        assertEquals("0.9.5", RootDetectionParser.parseManager(kernelSu, listOf("/system/bin/su")).version)

        val apatch = """
            SU_VERSION_BEGIN
            APatch_0.10.7
            APatch 0.10.7
            SU_VERSION_END
            MANAGER_BEGIN
            DIR:/data/adb/ap
            MANAGER_END
        """.trimIndent()
        assertEquals("0.10.7", RootDetectionParser.parseManager(apatch, listOf("/system/bin/su")).version)
    }

    @Test
    fun managerSpecificVersionLineIsPreferredOverSuOutput() {
        val output = """
            SU_VERSION_BEGIN
            27.0:MAGISK
            MAGISK 27.0
            SU_VERSION_END
            MANAGER_BEGIN
            DIR:/data/adb/magisk
            MAGISK_VERSION:27.0
            MANAGER_END
        """.trimIndent()
        val info = RootDetectionParser.parseManager(output, listOf("/system/bin/su"))
        assertEquals("27.0", info.version)
    }

    @Test
    fun normalizeVersionHandlesCombinedStringsAndJunk() {
        assertEquals("27.0", RootDetectionParser.normalizeVersion("27.0:MAGISK MAGISK 27.0"))
        assertEquals("0.9.5", RootDetectionParser.normalizeVersion("KernelSU_v0.9.5"))
        assertEquals("0.10.7", RootDetectionParser.normalizeVersion("APatch_0.10.7 APatch 0.10.7"))
        assertEquals(null, RootDetectionParser.normalizeVersion(null))
        assertEquals(null, RootDetectionParser.normalizeVersion(""))
        assertEquals(null, RootDetectionParser.normalizeVersion("MAGISK"))
    }

    @Test
    fun deniedRequestMessageDoesNotDuplicateManagerName() = runBlocking {
        val manager = RootManager(runner(id = TestShellResults.failure("Permission denied")))
        val status = manager.detect(request = true)
        assertEquals(RootState.DENIED, status.state)
        assertTrue(status.message.isNotBlank())
    }
}
