package com.spacexjr.part2dump.partition

import com.spacexjr.part2dump.device.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionGroupsTest {

    @Test
    fun classifyRecognisesBootChain() {
        for (name in listOf("boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "dtbo", "dt", "ramdisk", "bootimage")) {
            assertEquals(name, PartitionGroup.BOOT, PartitionGroups.classify(name))
        }
    }

    @Test
    fun classifyIgnoresSlotSuffix() {
        assertEquals(PartitionGroup.BOOT, PartitionGroups.classify("boot_a"))
        assertEquals(PartitionGroup.BOOT, PartitionGroups.classify("vendor_boot_b"))
        assertEquals(PartitionGroup.DATA, PartitionGroups.classify("userdata_b"))
    }

    @Test
    fun classifyIsCaseInsensitive() {
        assertEquals(PartitionGroup.DYNAMIC, PartitionGroups.classify("SUPER"))
        assertEquals(PartitionGroup.DATA, PartitionGroups.classify("UserData"))
        assertEquals(PartitionGroup.RECOVERY, PartitionGroups.classify("Recovery_a"))
    }

    @Test
    fun classifyRecognisesAvbPartitions() {
        assertEquals(PartitionGroup.AVB, PartitionGroups.classify("vbmeta"))
        assertEquals(PartitionGroup.AVB, PartitionGroups.classify("vbmeta_system_a"))
        assertEquals(PartitionGroup.AVB, PartitionGroups.classify("avb_custom_key"))
    }

    @Test
    fun classifyRecognisesDynamicNames() {
        for (name in listOf("super", "system", "system_ext", "system_dlkm", "product", "vendor", "odm", "oem")) {
            assertEquals(name, PartitionGroup.DYNAMIC, PartitionGroups.classify(name))
        }
        assertEquals(PartitionGroup.DYNAMIC, PartitionGroups.classify("my_stock"))
    }

    @Test
    fun classifyRecognisesRecoveryAndData() {
        assertEquals(PartitionGroup.RECOVERY, PartitionGroups.classify("recovery"))
        assertEquals(PartitionGroup.RECOVERY, PartitionGroups.classify("recovery_b"))
        for (name in listOf("userdata", "cache", "metadata", "persist", "persistency")) {
            assertEquals(name, PartitionGroup.DATA, PartitionGroups.classify(name))
        }
    }

    @Test
    fun classifyFallsBackToOther() {
        assertEquals(PartitionGroup.OTHER, PartitionGroups.classify("misc"))
        assertEquals(PartitionGroup.OTHER, PartitionGroups.classify("fsc"))
        assertEquals(PartitionGroup.OTHER, PartitionGroups.classify(""))
    }

    @Test
    fun ofFiltersByGroup() {
        val partitions = listOf(
            partition("boot_a", PartitionGroup.BOOT),
            partition("vbmeta_a", PartitionGroup.AVB),
            partition("system", PartitionGroup.DYNAMIC)
        )
        assertEquals(listOf("boot_a"), PartitionGroups.of(partitions, PartitionGroup.BOOT).map { it.name })
        assertEquals(1, PartitionGroups.of(partitions, PartitionGroup.DYNAMIC).size)
        assertTrue(PartitionGroups.of(partitions, PartitionGroup.RECOVERY).isEmpty())
    }

    @Test
    fun quickGroupsCoverTheCommonCases() {
        assertEquals(
            listOf(
                PartitionGroup.BOOT,
                PartitionGroup.AVB,
                PartitionGroup.DYNAMIC,
                PartitionGroup.RECOVERY
            ),
            PartitionGroups.quickGroups
        )
    }

    @Test
    fun partitionExposesDerivedFields() {
        val item = Partition(
            name = "boot_a",
            linkPath = "/dev/block/by-name/boot_a",
            devicePath = "/dev/block/sde1",
            sizeBytes = 64L * 1024 * 1024,
            slot = Slot.A,
            group = PartitionGroup.BOOT,
            isBlockDevice = true,
            readable = true
        )
        assertEquals("boot", item.baseName)
        assertEquals("boot_a.img", item.imageFileName)
        assertEquals("64.0 MiB", item.sizeLabel)
        assertTrue(item.hasKnownSize)
        assertEquals("Block device", item.typeLabel)
    }

    @Test
    fun partitionWithoutSizeIsMarkedUnknown() {
        val item = partition("system", PartitionGroup.DYNAMIC).copy(sizeBytes = -1L, readable = false, isBlockDevice = false)
        assertFalse(item.hasKnownSize)
        assertEquals("?", item.sizeLabel)
        assertEquals("Link inválido", item.typeLabel)
    }

    @Test
    fun logicalDynamicPartitionIsLabelledAsDm() {
        val item = partition("system", PartitionGroup.DYNAMIC, isLogicalDynamic = true)
        assertEquals("Dynamic partition (dm)", item.typeLabel)
    }

    private fun partition(
        name: String,
        group: PartitionGroup,
        isLogicalDynamic: Boolean = false
    ) = Partition(
        name = name,
        linkPath = "/dev/block/by-name/$name",
        devicePath = "/dev/block/sde9",
        sizeBytes = 1024L,
        slot = Slot.NONE,
        group = group,
        isBlockDevice = true,
        readable = true,
        isLogicalDynamic = isLogicalDynamic
    )
}
