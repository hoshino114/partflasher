package com.partflasher.app.core.partition

import com.partflasher.app.core.FakeCommandRunner
import com.partflasher.app.core.TestShellResults
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.root.RootManager
import com.partflasher.app.core.root.RootProbeScripts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionRepositoryTest {

    private val scanOutput = """
        boot_a|/dev/block/by-name/boot_a|/dev/block/sda15|67108864
        boot_a|/dev/block/by-name/boot_a|/dev/block/sda15|67108864
        init_boot_a|/dev/block/by-name/init_boot_a|/dev/block/sda13|8388608
        vendor_boot_a|/dev/block/by-name/vendor_boot_a|/dev/block/sda14|100663296
        userdata|/dev/block/by-name/userdata|/dev/block/sda17|117440512000
        system_a|/dev/block/by-name/system_a|/dev/block/sda10|1073741824
        aaa_other|/dev/block/by-name/aaa_other|/dev/block/sda90|1048576
        ---PARTITIONS-END---
        ---SLOT---
        _a
        ---MOUNTS---
        /dev/block/sda17 /data ext4 rw,seclabel 0 0
        /dev/block/sda10 /system ext4 ro,seclabel 0 0
    """.trimIndent()

    private val managerOutput = """
        SU_VERSION_BEGIN
        27.0:MAGISK
        SU_VERSION_END
        MANAGER_BEGIN
        DIR:/data/adb/magisk
        MAGISK_VERSION:27.0
        MANAGER_END
    """.trimIndent()

    private fun grantedRunner(
        scan: String = scanOutput,
        verify: String = "",
        verifyExit: Int = 0,
    ): FakeCommandRunner = FakeCommandRunner { command, _, asRoot ->
        when {
            command == RootProbeScripts.PROBE_SU && !asRoot -> TestShellResults.ok("/system/bin/su\n")
            command == "id" && asRoot -> TestShellResults.ok("uid=0(root) gid=0(root)\n")
            command == RootProbeScripts.MANAGEINFO && asRoot -> TestShellResults.ok(managerOutput)
            // Must be matched before the generic readlink check: the scan script
            // itself also calls `readlink -f`.
            command.contains("---PARTITIONS-END---") && asRoot -> TestShellResults.ok(scan)
            command.contains("readlink -f") && asRoot ->
                TestShellResults.failure(stdout = verify, exitCode = verifyExit)
            else -> TestShellResults.failure("unexpected command: ${command.take(80)}")
        }
    }

    private suspend fun loadedRepository(runner: FakeCommandRunner = grantedRunner()): Pair<PartitionRepository, List<com.partflasher.app.core.model.Partition>> {
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val repository = PartitionRepository(rootManager)
        val result = repository.load()
        return repository to result.partitions
    }

    @Test
    fun loadSortsBootFamilyFirstThenAlphabetically() = runBlocking {
        val (_, partitions) = loadedRepository()
        assertEquals(6, partitions.size) // duplicate boot_a deduplicated
        assertEquals(listOf("boot_a", "init_boot_a", "vendor_boot_a", "aaa_other", "system_a", "userdata"), partitions.map { it.name })
        assertEquals(PartitionKind.BOOT, partitions.first().kind)
    }

    @Test
    fun loadClassifiesRiskSlotsAndMounts() = runBlocking {
        val (_, partitions) = loadedRepository()
        val userdata = partitions.first { it.name == "userdata" }
        assertEquals(PartitionRisk.CRITICAL, userdata.risk)
        assertTrue(userdata.isMounted)
        assertEquals("/data", userdata.mountPoint)
        assertEquals("_a", partitions.first { it.name == "boot_a" }.slotSuffix)
        assertEquals(PartitionRisk.CAUTION, partitions.first { it.name == "boot_a" }.risk)
        assertEquals(PartitionRisk.NORMAL, partitions.first { it.name == "aaa_other" }.risk)
    }

    @Test
    fun loadReportsActiveSlotAndSourceDirectory() = runBlocking {
        val runner = grantedRunner()
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val result = PartitionRepository(rootManager).load()
        assertEquals("_a", result.activeSlot)
        assertEquals("/dev/block/by-name", result.sourceDirectory)
        assertTrue(result.warnings.none { it.contains("扫描分区表失败") })
    }

    @Test
    fun loadWithoutRootReturnsWarningInsteadOfThrowing() = runBlocking {
        val runner = FakeCommandRunner { command, _, asRoot ->
            when {
                command == RootProbeScripts.PROBE_SU && !asRoot -> TestShellResults.ok("")
                else -> TestShellResults.failure("should not run root command")
            }
        }
        val repository = PartitionRepository(RootManager(runner))
        val result = repository.load()
        assertTrue(result.partitions.isEmpty())
        assertTrue(result.warnings.any { it.contains("Root") })
    }

    @Test
    fun verifyTargetPassesWhenEverythingMatches() = runBlocking {
        val (_, partitions) = loadedRepository()
        val target = partitions.first { it.name == "boot_a" }
        val verifyRunner = grantedRunner(
            verify = "RESOLVED:/dev/block/sda15\nIS_BLOCK:1\nSIZE:67108864\n",
        )
        val rootManager = RootManager(verifyRunner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(target)
        assertTrue(check.message, check.ok)
        assertTrue(check.message.contains("boot_a"))
    }

    @Test
    fun verifyTargetFailsOnChangedSize() = runBlocking {
        val partition = com.partflasher.app.core.model.Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            blockPath = "/dev/block/sda15",
            sizeBytes = 67_108_864L,
            kind = PartitionKind.BOOT,
            risk = PartitionRisk.CAUTION,
        )
        val runner = grantedRunner(
            verify = "RESOLVED:/dev/block/sda15\nIS_BLOCK:1\nSIZE:12345\n",
        )
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(partition)
        assertFalse(check.ok)
        assertTrue(check.message.contains("大小已变化"))
    }

    @Test
    fun verifyTargetFailsWhenMappingChanged() = runBlocking {
        val partition = com.partflasher.app.core.model.Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            blockPath = "/dev/block/sda15",
            sizeBytes = 67_108_864L,
            kind = PartitionKind.BOOT,
            risk = PartitionRisk.CAUTION,
        )
        val runner = grantedRunner(
            verify = "RESOLVED:/dev/block/sda16\nIS_BLOCK:1\nSIZE:67108864\n",
        )
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(partition)
        assertFalse(check.ok)
        assertTrue(check.message.contains("映射已变化"))
    }

    @Test
    fun verifyTargetFailsWhenNotABlockDevice() = runBlocking {
        val partition = com.partflasher.app.core.model.Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            blockPath = "/dev/block/sda15",
            sizeBytes = 67_108_864L,
            kind = PartitionKind.BOOT,
            risk = PartitionRisk.CAUTION,
        )
        val runner = grantedRunner(
            verify = "RESOLVED:/dev/block/sda15\nIS_BLOCK:0\nSIZE:67108864\n",
        )
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(partition)
        assertFalse(check.ok)
        assertTrue(check.message.contains("不是块设备"))
    }

    @Test
    fun verifyTargetFailsWhenLinkMissing() = runBlocking {
        val partition = com.partflasher.app.core.model.Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            blockPath = "/dev/block/sda15",
            sizeBytes = 67_108_864L,
            kind = PartitionKind.BOOT,
            risk = PartitionRisk.CAUTION,
        )
        val runner = grantedRunner(verify = "TARGET_MISSING\n", verifyExit = 2)
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(partition)
        assertFalse(check.ok)
        assertTrue(check.message.contains("不存在"))
    }

    @Test
    fun verifyTargetRejectsPathOutsideDevBlock() = runBlocking {
        val partition = com.partflasher.app.core.model.Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            blockPath = "/dev/block/sda15",
            sizeBytes = 67_108_864L,
            kind = PartitionKind.BOOT,
            risk = PartitionRisk.CAUTION,
        )
        val runner = grantedRunner(
            verify = "RESOLVED:/system/bin/su\nIS_BLOCK:1\nSIZE:67108864\n",
        )
        val rootManager = RootManager(runner)
        rootManager.detect(request = true)
        val check = PartitionRepository(rootManager).verifyTarget(partition)
        assertFalse(check.ok)
        assertTrue(check.message.contains("/dev/block"))
    }
}
