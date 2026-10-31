package com.partflasher.app.core.partition

/** One raw line emitted by the partition scan script. */
internal data class RawPartition(
    val name: String,
    val linkPath: String,
    val blockPath: String,
    val sizeBytes: Long,
)

/** One `/proc/mounts` style mount source/mountpoint pair. */
internal data class MountEntry(
    val source: String,
    val mountPoint: String,
)

internal data class ParsedPartitionOutput(
    val partitions: List<RawPartition>,
    val activeSlot: String?,
    val mounts: List<MountEntry>,
    val warnings: List<String>,
)

/**
 * Parses the pipe-delimited output of the partition scan script:
 *
 * ```
 * <name>|<by-name link>|<resolved block device>|<size bytes>
 * ---PARTITIONS-END---
 * ---SLOT---
 * _a
 * ---MOUNTS---
 * /dev/block/sda1 /data ext4 rw,seclabel 0 0
 * ```
 */
internal object PartitionOutputParser {

    const val SECTION_PARTITIONS_END = "---PARTITIONS-END---"
    const val SECTION_SLOT = "---SLOT---"
    const val SECTION_MOUNTS = "---MOUNTS---"

    private enum class Section { PARTITIONS, SLOT, MOUNTS }

    fun parse(output: String): ParsedPartitionOutput {
        val partitions = mutableListOf<RawPartition>()
        val warnings = mutableListOf<String>()
        val slotLines = mutableListOf<String>()
        val mountLines = mutableListOf<String>()
        var section = Section.PARTITIONS

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd()
            when {
                line.trim() == SECTION_PARTITIONS_END -> {
                    section = Section.PARTITIONS
                    continue
                }
                line.trim() == SECTION_SLOT -> {
                    section = Section.SLOT
                    continue
                }
                line.trim() == SECTION_MOUNTS -> {
                    section = Section.MOUNTS
                    continue
                }
            }
            when (section) {
                Section.PARTITIONS -> {
                    if (line.isBlank()) continue
                    val fields = line.split('|')
                    if (fields.size != 4) {
                        warnings.add("忽略无法解析的分区行：${line.take(160)}")
                        continue
                    }
                    val name = fields[0]
                    val link = fields[1]
                    val resolved = fields[2]
                    val size = fields[3].trim().toLongOrNull()
                    if (name.isBlank() || link.isBlank() || resolved.isBlank()) {
                        warnings.add("忽略字段缺失的分区行：${line.take(160)}")
                        continue
                    }
                    if (size == null || size < 0L) {
                        warnings.add("分区 $name 的大小无法解析，按未知大小处理。")
                    }
                    partitions.add(RawPartition(name, link, resolved, (size ?: 0L).coerceAtLeast(0L)))
                }

                Section.SLOT -> {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) slotLines.add(trimmed)
                }

                Section.MOUNTS -> {
                    if (line.isNotBlank()) mountLines.add(line)
                }
            }
        }

        return ParsedPartitionOutput(
            partitions = partitions,
            activeSlot = parseActiveSlot(slotLines),
            mounts = parseMounts(mountLines),
            warnings = warnings,
        )
    }

    internal fun parseActiveSlot(slotLines: List<String>): String? {
        for (line in slotLines) {
            val trimmed = line.trim()
            if (trimmed.matches(Regex("""_[ab]"""))) return trimmed
            if (trimmed.matches(Regex("""[ab]"""))) return "_$trimmed"
        }
        return null
    }

    internal fun parseMounts(lines: List<String>): List<MountEntry> {
        val mounts = mutableListOf<MountEntry>()
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 2) continue
            val source = fields[0].replace("\\040", " ")
            val mountPoint = fields[1].replace("\\040", " ")
            if (!source.startsWith("/dev/")) continue
            mounts.add(MountEntry(source = source, mountPoint = mountPoint))
        }
        return mounts
    }

    /**
     * Finds the mount point for a block device, matching the resolved path, the
     * by-name link, or (for dm/loop devices) the device basename.
     */
    fun mountPointFor(
        blockPath: String,
        linkPath: String,
        mounts: List<MountEntry>,
    ): String? {
        mounts.firstOrNull { it.source == blockPath }?.let { return it.mountPoint }
        mounts.firstOrNull { it.source == linkPath }?.let { return it.mountPoint }
        val blockBase = blockPath.substringAfterLast('/')
        if (blockBase.isNotBlank()) {
            mounts.firstOrNull { it.source.substringAfterLast('/') == blockBase }?.let { return it.mountPoint }
        }
        return null
    }
}
