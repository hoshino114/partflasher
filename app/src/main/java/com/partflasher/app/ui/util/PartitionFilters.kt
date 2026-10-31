package com.partflasher.app.ui.util

import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind

/**
 * Display ordering and filtering rules for the partition list. Keeping these
 * pure functions next to each other makes the screen logic trivial and unit
 * testable.
 */
private fun kindPriority(kind: PartitionKind): Int = when (kind) {
    PartitionKind.BOOT -> 0
    PartitionKind.INIT_BOOT -> 1
    PartitionKind.VENDOR_BOOT -> 2
    PartitionKind.RECOVERY -> 3
    PartitionKind.DTBO -> 4
    PartitionKind.VBMETA -> 5
    PartitionKind.SUPER -> 6
    PartitionKind.SYSTEM -> 7
    PartitionKind.VENDOR -> 8
    PartitionKind.PRODUCT -> 9
    PartitionKind.ODM -> 10
    PartitionKind.FIRMWARE -> 11
    PartitionKind.MODEM -> 12
    PartitionKind.DATA -> 13
    PartitionKind.METADATA -> 14
    PartitionKind.MISC -> 15
    PartitionKind.PERSIST -> 16
    PartitionKind.OTHER -> 17
}

private val displayComparator: Comparator<Partition> =
    compareBy<Partition>(
        { kindPriority(it.kind) },
        { it.baseName.lowercase() },
        { it.slotSuffix ?: "" },
    )

/** Boot-family partitions in canonical order (used for the grouped list). */
fun List<Partition>.bootFamilySorted(): List<Partition> =
    filter { it.isBootFamily }.sortedWith(displayComparator)

/** Everything else, sorted by kind then name. */
fun List<Partition>.otherPartitionsSorted(): List<Partition> =
    filterNot { it.isBootFamily }.sortedWith(displayComparator)

/**
 * Case-insensitive search across the partition name, block path, description
 * and mount point. A blank query returns the list unchanged.
 */
fun List<Partition>.filterPartitions(query: String): List<Partition> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { partition ->
        partition.name.contains(needle, ignoreCase = true) ||
            partition.blockPath.contains(needle, ignoreCase = true) ||
            partition.linkPath.contains(needle, ignoreCase = true) ||
            partition.description.contains(needle, ignoreCase = true) ||
            partition.mountPoint?.contains(needle, ignoreCase = true) == true
    }
}
