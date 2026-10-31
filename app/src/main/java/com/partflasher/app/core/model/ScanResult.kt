package com.partflasher.app.core.model

/** Result of scanning the device's partition table. */
data class PartitionScanResult(
    val partitions: List<Partition> = emptyList(),
    val sourceDirectory: String? = null,
    val activeSlot: String? = null,
    val warnings: List<String> = emptyList(),
)
