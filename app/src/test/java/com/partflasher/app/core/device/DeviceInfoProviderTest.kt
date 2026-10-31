package com.partflasher.app.core.device

import com.partflasher.app.core.FakeCommandRunner
import com.partflasher.app.core.TestShellResults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoProviderTest {

    private val snapshot = BuildSnapshot(
        manufacturer = "Google",
        model = "Pixel 7",
        device = "panther",
        androidRelease = "16",
        sdkInt = 36,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        securityPatch = "2025-06-05",
        kernelVersion = null,
        fingerprint = "google/panther/panther:16/BP1A/123:user/release-keys",
        isEmulator = false,
    )

    private fun provider(
        stdout: String = "_b\nb\ntrue\ntrue\n2025-06-01\n6.1.75-android14-g123\n",
        asRootForbidden: Boolean = false,
    ): DeviceInfoProvider {
        val runner = FakeCommandRunner { command, _, asRoot ->
            assertFalse("DeviceInfoProvider must never use root", asRoot)
            if (asRootForbidden) TestShellResults.failure("no root") else TestShellResults.ok(stdout)
        }
        return DeviceInfoProvider(runner) { snapshot }
    }

    @Test
    fun combinesBuildValuesAndProbes() = runBlocking {
        val info = provider().load()
        assertEquals("Google", info.manufacturer)
        assertEquals("Pixel 7", info.model)
        assertEquals("panther", info.device)
        assertEquals("16", info.androidRelease)
        assertEquals(36, info.sdkInt)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), info.supportedAbis)
        assertEquals("_b", info.activeSlot)
        assertTrue(info.dynamicPartitions)
        assertTrue(info.virtualAb)
        assertEquals("2025-06-01", info.securityPatch)
        assertEquals("6.1.75-android14-g123", info.kernelVersion)
        assertFalse(info.isEmulator)
        assertTrue(info.isArm64)
        assertEquals("B", info.slotLabel)
    }

    @Test
    fun legacySlotPropertyIsNormalized() = runBlocking {
        val info = provider(stdout = "\na\nfalse\nfalse\n\n6.1.0\n").load()
        assertEquals("_a", info.activeSlot)
        assertFalse(info.dynamicPartitions)
        assertFalse(info.virtualAb)
        // No patch line in the probe: the Build snapshot is used as fallback.
        assertEquals("2025-06-05", info.securityPatch)
    }

    @Test
    fun emptyProbeFallsBackToBuildSnapshot() = runBlocking {
        val buildWithPatch = snapshot.copy(securityPatch = "2024-01-01", kernelVersion = "5.10.0")
        val runner = FakeCommandRunner { _, _, _ -> TestShellResults.ok("") }
        val provider = DeviceInfoProvider(runner) { buildWithPatch }
        val info = provider.load()
        assertNull(info.activeSlot)
        assertFalse(info.dynamicPartitions)
        assertEquals("2024-01-01", info.securityPatch)
        assertEquals("5.10.0", info.kernelVersion)
    }

    @Test
    fun failingProbeDoesNotThrow() = runBlocking {
        val provider = provider(asRootForbidden = true)
        val info = provider.load()
        assertEquals("Pixel 7", info.model)
        assertNull(info.activeSlot)
    }

    @Test
    fun probeParserHandlesUnsetProperties() {
        val values = DeviceProbeParser.parse("\n\n\n\n\n\n")
        assertNull(values.slotSuffix)
        assertNull(values.slot)
        assertFalse(values.dynamicPartitions)
        assertFalse(values.virtualAb)
        assertNull(values.securityPatch)
        assertNull(values.kernelVersion)
    }

    @Test
    fun probeParserParsesOrderedLines() {
        val values = DeviceProbeParser.parse("_a\na\ntrue\nfalse\n2025-01-01\n6.6.1\n")
        assertEquals("_a", values.slotSuffix)
        assertEquals("a", values.slot)
        assertTrue(values.dynamicPartitions)
        assertFalse(values.virtualAb)
        assertEquals("2025-01-01", values.securityPatch)
        assertEquals("6.6.1", values.kernelVersion)
    }

    @Test
    fun emulatorDetectionComesFromBuildSnapshot() = runBlocking {
        val emulator = snapshot.copy(
            manufacturer = "Google",
            model = "sdk_gphone64_x86_64",
            device = "emu64x",
            isEmulator = true,
            supportedAbis = listOf("x86_64"),
        )
        val runner = FakeCommandRunner { _, _, _ -> TestShellResults.ok("") }
        val info = DeviceInfoProvider(runner) { emulator }.load()
        assertTrue(info.isEmulator)
        assertFalse(info.isArm64)
        assertEquals("无 (非 A/B)", info.slotLabel)
    }
}
