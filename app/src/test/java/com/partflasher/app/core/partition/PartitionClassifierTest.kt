package com.partflasher.app.core.partition

import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionClassifierTest {

    @Test
    fun bootPartitionsWithSlotsAreClassifiedAsBootCaution() {
        for (name in listOf("boot_a", "boot_b")) {
            val partition = PartitionClassifier.classify(
                name = name,
                linkPath = "/dev/block/by-name/$name",
                blockPath = "/dev/block/sda15",
                sizeBytes = 64L * 1024 * 1024,
            )
            assertEquals(PartitionKind.BOOT, partition.kind)
            assertEquals(name.takeLast(2), partition.slotSuffix)
            assertEquals("boot", partition.baseName)
            assertEquals(name.takeLast(1).uppercase(), partition.slotLabel)
            assertEquals(PartitionRisk.CAUTION, partition.risk)
            assertTrue(partition.isBootFamily)
            assertTrue(partition.description.contains("boot"))
            assertNull(partition.protectedReason)
        }
    }

    @Test
    fun allBootFamilyKindsAndSlotsAreClassified() {
        val expectations = mapOf(
            "init_boot_a" to PartitionKind.INIT_BOOT,
            "vendor_boot_b" to PartitionKind.VENDOR_BOOT,
            "recovery" to PartitionKind.RECOVERY,
            "dtbo_a" to PartitionKind.DTBO,
            "vbmeta_a" to PartitionKind.VBMETA,
            "vbmeta_system_b" to PartitionKind.VBMETA,
        )
        for ((name, kind) in expectations) {
            val partition = PartitionClassifier.classify(name, "/dev/block/by-name/$name", "/dev/block/sda1", 1024L)
            assertEquals("wrong kind for $name", kind, partition.kind)
        }
    }

    @Test
    fun filesystemAndContainerKindsAreClassified() {
        assertEquals(PartitionKind.SUPER, PartitionClassifier.kindOf("super"))
        assertEquals(PartitionKind.SYSTEM, PartitionClassifier.kindOf("system"))
        assertEquals(PartitionKind.SYSTEM, PartitionClassifier.kindOf("system_ext"))
        assertEquals(PartitionKind.VENDOR, PartitionClassifier.kindOf("vendor"))
        assertEquals(PartitionKind.PRODUCT, PartitionClassifier.kindOf("product"))
        assertEquals(PartitionKind.ODM, PartitionClassifier.kindOf("odm"))
        assertEquals(PartitionKind.DATA, PartitionClassifier.kindOf("userdata"))
        assertEquals(PartitionKind.METADATA, PartitionClassifier.kindOf("metadata"))
        assertEquals(PartitionKind.PERSIST, PartitionClassifier.kindOf("persist"))
        assertEquals(PartitionKind.MISC, PartitionClassifier.kindOf("misc"))
        assertEquals(PartitionKind.MODEM, PartitionClassifier.kindOf("modem"))
        assertEquals(PartitionKind.FIRMWARE, PartitionClassifier.kindOf("xbl"))
        assertEquals(PartitionKind.OTHER, PartitionClassifier.kindOf("randomthing"))
    }

    @Test
    fun protectedPartitionsAreCriticalWithChineseReason() {
        val protected = listOf(
            "userdata", "data", "metadata", "persist", "frp", "devinfo", "keystore",
            "misc", "ssd", "modemst1", "modemst2", "fsg", "efs",
        )
        for (name in protected) {
            assertTrue("$name must be protected", PartitionClassifier.isProtectedBase(name))
            val partition = PartitionClassifier.classify(
                name = name,
                linkPath = "/dev/block/by-name/$name",
                blockPath = "/dev/block/sda1",
                sizeBytes = 1024L,
            )
            assertEquals("$name should be CRITICAL", PartitionRisk.CRITICAL, partition.risk)
            assertTrue(partition.isProtected)
            assertNotNull("$name needs a reason", partition.protectedReason)
            assertTrue(
                "reason for $name must be Chinese: ${partition.protectedReason}",
                partition.protectedReason.orEmpty().contains("："),
            )
        }
    }

    @Test
    fun superIsCautionButNotProtected() {
        val partition = PartitionClassifier.classify("super", "/dev/block/by-name/super", "/dev/block/sda9", 8L * 1024 * 1024 * 1024)
        assertEquals(PartitionKind.SUPER, partition.kind)
        assertEquals(PartitionRisk.CAUTION, partition.risk)
        assertFalse(partition.isProtected)
        assertNull(partition.protectedReason)
    }

    @Test
    fun sortOrderPutsBootFamilyFirst() {
        val bootOrder = PartitionClassifier.sortOrder(PartitionKind.BOOT)
        val initBootOrder = PartitionClassifier.sortOrder(PartitionKind.INIT_BOOT)
        val vendorBootOrder = PartitionClassifier.sortOrder(PartitionKind.VENDOR_BOOT)
        val dtboOrder = PartitionClassifier.sortOrder(PartitionKind.DTBO)
        val vbmetaOrder = PartitionClassifier.sortOrder(PartitionKind.VBMETA)
        val recoveryOrder = PartitionClassifier.sortOrder(PartitionKind.RECOVERY)
        val systemOrder = PartitionClassifier.sortOrder(PartitionKind.SYSTEM)
        assertTrue(bootOrder < initBootOrder)
        assertTrue(initBootOrder < vendorBootOrder)
        assertTrue(vendorBootOrder < dtboOrder)
        assertTrue(dtboOrder < vbmetaOrder)
        assertTrue(vbmetaOrder < recoveryOrder)
        assertTrue(recoveryOrder < systemOrder)
    }

    @Test
    fun slotSuffixHandling() {
        assertEquals("_a", PartitionClassifier.slotSuffix("boot_a"))
        assertEquals("_b", PartitionClassifier.slotSuffix("vendor_boot_b"))
        assertNull(PartitionClassifier.slotSuffix("boot"))
        assertNull(PartitionClassifier.slotSuffix("sda"))
        assertEquals("vendor_boot", PartitionClassifier.baseName("vendor_boot_b"))
        assertEquals("recovery", PartitionClassifier.baseName("recovery"))
    }

    @Test
    fun mountedFlagIsPropagated() {
        val partition = PartitionClassifier.classify(
            name = "userdata",
            linkPath = "/dev/block/by-name/userdata",
            blockPath = "/dev/block/sda17",
            sizeBytes = 1024L,
            isMounted = true,
            mountPoint = "/data",
        )
        assertTrue(partition.isMounted)
        assertEquals("/data", partition.mountPoint)
    }
}
