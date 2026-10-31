package com.partflasher.app.core.root

import com.partflasher.app.core.model.RootSolution
import com.partflasher.app.core.model.RootState

/** Shell scripts used by [RootManager]; exposed for unit tests. */
internal object RootProbeScripts {

    /** Unprivileged probe: never executes `su`, only looks for it. */
    const val PROBE_SU: String =
        "command -v su 2>/dev/null\n" +
            "for p in /system/bin/su /system/xbin/su /sbin/su /debug_ramdisk/su " +
            "/data/adb/ksu/bin/su /data/adb/magisk/su; do\n" +
            "  [ -x \"\$p\" ] && echo \"\$p\"\n" +
            "done\n" +
            "exit 0"

    /** Root-side probe for the installed root solution, its version and manager binaries. */
    const val MANAGEINFO: String =
        "echo \"SU_VERSION_BEGIN\"\n" +
            "SU_BIN=$(command -v su 2>/dev/null)\n" +
            "if [ -n \"\$SU_BIN\" ]; then\n" +
            "  \"\$SU_BIN\" -v 2>/dev/null\n" +
            "  \"\$SU_BIN\" --version 2>/dev/null\n" +
            "fi\n" +
            "echo \"SU_VERSION_END\"\n" +
            "echo \"MANAGER_BEGIN\"\n" +
            "for d in /data/adb/magisk /data/adb/modules /data/adb/ksu /data/adb/ksud " +
            "/data/adb/ap /data/adb/apatch /data/adb/apd; do\n" +
            "  [ -e \"\$d\" ] && echo \"DIR:\$d\"\n" +
            "done\n" +
            "for b in magisk ksud apd; do\n" +
            "  p=$(command -v \"\$b\" 2>/dev/null)\n" +
            "  [ -n \"\$p\" ] && echo \"BIN:\$b:\$p\"\n" +
            "done\n" +
            "[ -x /data/adb/magisk/magisk ] && echo \"MAGISK_VERSION:$(/data/adb/magisk/magisk -v 2>/dev/null | head -n 1)\"\n" +
            "[ -x /data/adb/ksu/bin/ksud ] && echo \"KSU_VERSION:$(/data/adb/ksu/bin/ksud -V 2>/dev/null | head -n 1)\"\n" +
            "[ -x /data/adb/ap/bin/apd ] && echo \"APATCH_VERSION:$(/data/adb/ap/bin/apd -V 2>/dev/null | head -n 1)\"\n" +
            "[ -x /data/adb/ksud ] && echo \"KSU_VERSION:$(/data/adb/ksud -V 2>/dev/null | head -n 1)\"\n" +
            "[ -x /data/adb/apd ] && echo \"APATCH_VERSION:$(/data/adb/apd -V 2>/dev/null | head -n 1)\"\n" +
            "echo \"MANAGER_END\""
}

/** Pure parsing helpers for the output of [RootProbeScripts]; unit-testable off-device. */
internal object RootDetectionParser {

    data class ManagerInfo(
        val solution: RootSolution,
        val version: String?,
        val suPath: String?,
        val detail: String?,
    )

    /** Extracts absolute `su` candidate paths from probe output, preserving order and deduplicating. */
    fun parseSuPaths(stdout: String): List<String> {
        val paths = LinkedHashSet<String>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("/") && line.length > 1) paths.add(line)
        }
        return paths.toList()
    }

    /** Parses `uid=0(root) gid=0(root)` (or a bare `0`) into a uid. */
    fun parseUid(stdout: String, stderr: String): Int? {
        val combined = "$stdout\n$stderr"
        Regex("""\buid=(\d+)""").find(combined)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        val trimmed = stdout.trim()
        if (trimmed.matches(Regex("""\d+"""))) return trimmed.toIntOrNull()
        Regex("""^(\d+)\(?""").find(trimmed)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * Classifies a failed (non-timeout) authorization attempt. `su` exiting
     * non-zero almost always means the manager denied the request; only a
     * missing/broken binary suggests the device is not rooted.
     */
    fun classifyFailure(result: ShellResult): RootState {
        if (result.timedOut) return RootState.ERROR
        if (result.isSuccess) return RootState.GRANTED
        val text = result.combinedOutput.lowercase()
        return when {
            text.contains("not found") || text.contains("no such file") ||
                text.contains("inaccessible") || text.contains("exec format") -> RootState.NOT_ROOTED
            else -> RootState.DENIED
        }
    }

    /** Parses the root-side manager probe output. */
    fun parseManager(output: String, suPaths: List<String>): ManagerInfo {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val dirs = lines.filter { it.startsWith("DIR:") }
            .map { it.removePrefix("DIR:") }
            .toSet()
        val bins = lines.filter { it.startsWith("BIN:") }.map { it.removePrefix("BIN:") }
        fun prefixed(prefix: String): String? =
            lines.firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val suVersionLines = lines
            .dropWhile { it != "SU_VERSION_BEGIN" }
            .drop(1)
            .takeWhile { it != "SU_VERSION_END" }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val suVersionRaw = suVersionLines.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        val suVersionLower = suVersionRaw?.lowercase().orEmpty()

        val hasMagisk = dirs.contains("/data/adb/magisk") ||
            bins.any { it.startsWith("magisk:") } ||
            suVersionLower.contains("magisk") ||
            prefixed("MAGISK_VERSION:") != null
        val hasKernelSu = dirs.contains("/data/adb/ksu") ||
            dirs.contains("/data/adb/ksud") ||
            bins.any { it.startsWith("ksud:") } ||
            suVersionLower.contains("kernelsu") ||
            prefixed("KSU_VERSION:") != null
        val hasApatch = dirs.contains("/data/adb/ap") ||
            dirs.contains("/data/adb/apatch") ||
            dirs.contains("/data/adb/apd") ||
            bins.any { it.startsWith("apd:") } ||
            suVersionLower.contains("apatch") ||
            prefixed("APATCH_VERSION:") != null

        val solution: RootSolution
        val version: String?
        when {
            hasApatch -> {
                solution = RootSolution.APATCH
                version = normalizeVersion(prefixed("APATCH_VERSION:")) ?: normalizeVersion(suVersionRaw)
            }
            hasKernelSu -> {
                solution = RootSolution.KERNELSU
                version = normalizeVersion(prefixed("KSU_VERSION:")) ?: normalizeVersion(suVersionRaw)
            }
            hasMagisk -> {
                solution = RootSolution.MAGISK
                version = normalizeVersion(prefixed("MAGISK_VERSION:")) ?: normalizeVersion(suVersionRaw)
            }
            suPaths.isNotEmpty() -> {
                solution = RootSolution.OTHER
                version = normalizeVersion(suVersionRaw)
            }
            else -> {
                solution = RootSolution.NONE
                version = null
            }
        }

        val suPath = suPaths.firstOrNull()
        return ManagerInfo(
            solution = solution,
            version = version,
            suPath = suPath,
            detail = output.trim().take(4000).takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Extracts one clean, manager-name-free version number from combined
     * `su -v` / `su --version` output (e.g. `27.0:MAGISK` + `MAGISK 27.0` ->
     * `27.0`, `KernelSU_v0.9.5` -> `0.9.5`) so the UI never shows a duplicated
     * manager name or a concatenation of both outputs.
     */
    internal fun normalizeVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        VERSION_PATTERN.find(raw)?.let { return it.value }
        // Some managers print a bare build number instead of a dotted version.
        return BARE_NUMBER_PATTERN.find(raw)?.value
    }

    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+){1,3}""")
    private val BARE_NUMBER_PATTERN = Regex("""\b\d{2,}\b""")

    /** Human-readable solution label for messages. */
    fun solutionLabel(solution: RootSolution): String = when (solution) {
        RootSolution.MAGISK -> "Magisk"
        RootSolution.KERNELSU -> "KernelSU"
        RootSolution.APATCH -> "APatch"
        RootSolution.OTHER -> "未知 Root 方案"
        RootSolution.NONE -> "无"
        RootSolution.UNKNOWN -> "未知"
    }
}
