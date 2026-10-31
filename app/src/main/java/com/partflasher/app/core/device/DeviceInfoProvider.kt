package com.partflasher.app.core.device

import android.os.Build
import com.partflasher.app.core.model.DeviceInfo
import com.partflasher.app.core.root.CommandRunner
import kotlinx.coroutines.CancellationException

/**
 * Collects Build.* values plus a few unprivileged `getprop` probes.
 *
 * This provider never prompts for root: all commands run through the plain
 * `sh -c` path. The Build snapshot is injected so the parsing logic can be unit
 * tested off-device.
 */
class DeviceInfoProvider(
    private val runner: CommandRunner,
    private val buildProvider: () -> BuildSnapshot = { BuildSnapshot.fromBuild() },
) {
    suspend fun load(): DeviceInfo {
        val build = runCatching { buildProvider() }.getOrElse { BuildSnapshot() }
        val probe = try {
            runner.run(PROBE_COMMAND, PROBE_TIMEOUT_MILLIS, asRoot = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val values = DeviceProbeParser.parse(probe?.stdout.orEmpty())
        val activeSlot = values.slotSuffix?.takeIf { it.matches(Regex("""_[ab]""")) }
            ?: values.slot?.takeIf { it.matches(Regex("""[ab]""")) }?.let { "_$it" }
        return DeviceInfo(
            manufacturer = build.manufacturer,
            model = build.model,
            device = build.device,
            androidRelease = build.androidRelease,
            sdkInt = build.sdkInt,
            supportedAbis = build.supportedAbis,
            activeSlot = activeSlot,
            dynamicPartitions = values.dynamicPartitions || build.dynamicPartitions,
            virtualAb = values.virtualAb,
            isEmulator = build.isEmulator,
            // Live getprop values win over the Build snapshot; Build is the fallback.
            securityPatch = values.securityPatch ?: build.securityPatch,
            kernelVersion = values.kernelVersion ?: build.kernelVersion,
            buildFingerprint = build.fingerprint,
        )
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 10_000L

        /**
         * Single unprivileged probe so the whole device card costs one process:
         * slot suffix, legacy slot, dynamic partitions, virtual A/B, security
         * patch and kernel release (one line each, in order).
         */
        const val PROBE_COMMAND: String =
            "getprop ro.boot.slot_suffix 2>/dev/null; " +
                "getprop ro.boot.slot 2>/dev/null; " +
                "getprop ro.boot.dynamic_partitions 2>/dev/null; " +
                "getprop ro.virtual_ab.enabled 2>/dev/null; " +
                "getprop ro.build.version.security_patch 2>/dev/null; " +
                "uname -r 2>/dev/null"
    }
}

/** Snapshot of `android.os.Build` so parsing can be unit tested. */
data class BuildSnapshot(
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val supportedAbis: List<String> = emptyList(),
    val securityPatch: String? = null,
    val kernelVersion: String? = null,
    val fingerprint: String? = null,
    val isEmulator: Boolean = false,
    val dynamicPartitions: Boolean = false,
) {
    companion object {
        fun fromBuild(): BuildSnapshot {
            val hardware = Build.HARDWARE.orEmpty().lowercase()
            val product = Build.PRODUCT.orEmpty().lowercase()
            val fingerprint = Build.FINGERPRINT.orEmpty()
            val isEmulator = hardware in setOf("goldfish", "ranchu") ||
                product.startsWith("sdk") ||
                product.contains("emulator") ||
                fingerprint.contains("generic")
            return BuildSnapshot(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
                securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() },
                kernelVersion = null,
                fingerprint = fingerprint.takeIf { it.isNotBlank() },
                isEmulator = isEmulator,
                dynamicPartitions = false,
            )
        }
    }
}

/** Parsed `getprop`/`uname` probe values. */
data class DeviceProbeValues(
    val slotSuffix: String? = null,
    val slot: String? = null,
    val dynamicPartitions: Boolean = false,
    val virtualAb: Boolean = false,
    val securityPatch: String? = null,
    val kernelVersion: String? = null,
)

/** Pure parser for the ordered [DeviceInfoProvider] probe output. */
object DeviceProbeParser {

    fun parse(stdout: String): DeviceProbeValues {
        // Keep empty lines: the probe prints one line per property, and an
        // unset property is an empty line.
        val lines = stdout.split('\n').map { it.trim() }
        fun valueAt(index: Int): String? = lines.getOrNull(index)?.takeIf { it.isNotBlank() }
        return DeviceProbeValues(
            slotSuffix = valueAt(0),
            slot = valueAt(1),
            dynamicPartitions = valueAt(2)?.toBooleanStrictOrNull() ?: false,
            virtualAb = valueAt(3)?.toBooleanStrictOrNull() ?: false,
            securityPatch = valueAt(4),
            kernelVersion = valueAt(5),
        )
    }
}
