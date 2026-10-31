package com.partflasher.app.core.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionOutputParserTest {

    private val fixture = """
        boot_a|/dev/block/by-name/boot_a|/dev/block/sda15|67108864
        boot_b|/dev/block/by-name/boot_b|/dev/block/sda16|67108864
        init_boot_a|/dev/block/by-name/init_boot_a|/dev/block/sda13|8388608
        vendor_boot_a|/dev/block/by-name/vendor_boot_a|/dev/block/sda14|100663296
        dtbo_a|/dev/block/by-name/dtbo_a|/dev/block/sda12|8388608
        vbmeta_a|/dev/block/by-name/vbmeta_a|/dev/block/sda11|8192
        super|/dev/block/by-name/super|/dev/block/sda9|8589934592
        system_a|/dev/block/by-name/system_a|/dev/block/dm-3|0
        userdata|/dev/block/by-name/userdata|/dev/block/sda17|117440512000
        modemst1|/dev/block/by-name/modemst1|/dev/block/sda20|2097152
        weird|/dev/block/by-name/weird|/dev/block/sda99|not-a-size
        not-a-partition-line
        ---PARTITIONS-END---
        ---SLOT---
        _a
        ---MOUNTS---
        /dev/block/sda17 /data ext4 rw,seclabel,relatime 0 0
        /dev/block/dm-3 /system ext4 ro,seclabel,relatime 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid 0 0
        /dev/block/by-name/userdata /mnt/alias ext4 rw 0 0
    """.trimIndent()

    @Test
    fun parsesAllPartitionLinesAndSizes() {
        val parsed = PartitionOutputParser.parse(fixture)
        assertEquals(11, parsed.partitions.size)
        val boot = parsed.partitions.first { it.name == "boot_a" }
        assertEquals("/dev/block/by-name/boot_a", boot.linkPath)
        assertEquals("/dev/block/sda15", boot.blockPath)
        assertEquals(67_108_864L, boot.sizeBytes)
        assertEquals(0L, parsed.partitions.first { it.name == "weird" }.sizeBytes)
        assertTrue(parsed.warnings.any { it.contains("weird") })
        assertTrue(parsed.warnings.any { it.contains("not-a-partition-line") })
    }

    @Test
    fun parsesActiveSlotFromSlotSection() {
        assertEquals("_a", PartitionOutputParser.parse(fixture).activeSlot)
        assertEquals(
            "_b",
            PartitionOutputParser.parse("---PARTITIONS-END---\n---SLOT---\nb\n").activeSlot,
        )
        assertNull(PartitionOutputParser.parse("---PARTITIONS-END---\n---SLOT---\n\n").activeSlot)
    }

    @Test
    fun parsesMountsAndMapsThemToPartitions() {
        val parsed = PartitionOutputParser.parse(fixture)
        assertEquals(3, parsed.mounts.size)
        assertEquals("/data", PartitionOutputParser.mountPointFor("/dev/block/sda17", "/dev/block/by-name/userdata", parsed.mounts))
        assertEquals("/system", PartitionOutputParser.mountPointFor("/dev/block/dm-3", "/dev/block/by-name/system_a", parsed.mounts))
        assertNull(PartitionOutputParser.mountPointFor("/dev/block/sdz1", "/dev/block/by-name/sdz1", parsed.mounts))
    }

    @Test
    fun escapeSequencesInMountsAreDecoded() {
        val mounts = PartitionOutputParser.parseMounts(
            listOf("/dev/block/sda1 /mnt/with\\040space ext4 rw 0 0"),
        )
        assertEquals("/mnt/with space", mounts.single().mountPoint)
    }

    @Test
    fun emptyOutputProducesEmptyResult() {
        val parsed = PartitionOutputParser.parse("")
        assertTrue(parsed.partitions.isEmpty())
        assertTrue(parsed.mounts.isEmpty())
        assertNull(parsed.activeSlot)
    }

    @Test
    fun malformedLinesAreIgnoredWithWarnings() {
        val parsed = PartitionOutputParser.parse("a|b|c\n|||\nboot_a|/dev/block/by-name/boot_a|/dev/block/sda1|4096\n")
        assertEquals(1, parsed.partitions.size)
        assertTrue(parsed.warnings.isNotEmpty())
    }
}
