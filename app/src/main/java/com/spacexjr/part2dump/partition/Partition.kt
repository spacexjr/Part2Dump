package com.spacexjr.part2dump.partition

import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.device.Slot

enum class PartitionGroup(val label: String) {
    BOOT("Boot"),
    AVB("AVB"),
    RECOVERY("Recovery"),
    DYNAMIC("Dinâmicas"),
    DATA("Dados"),
    OTHER("Outras")
}

object PartitionGroups {

    private val BOOT_NAMES = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vendor_kernel_boot",
        "dtbo",
        "dt",
        "ramdisk",
        "bootimage"
    )

    private val DYNAMIC_NAMES = setOf(
        "super",
        "system",
        "system_ext",
        "system_dlkm",
        "product",
        "vendor",
        "odm",
        "oem"
    )

    private val DATA_NAMES = setOf(
        "userdata",
        "cache",
        "metadata",
        "persist",
        "persistency"
    )

    fun classify(name: String): PartitionGroup {
        val base = Slot.stripSuffix(name).lowercase()
        return when {
            base.startsWith("vbmeta") || base.startsWith("avb") -> PartitionGroup.AVB
            base == "recovery" -> PartitionGroup.RECOVERY
            base in DYNAMIC_NAMES || base.startsWith("my_") -> PartitionGroup.DYNAMIC
            base in BOOT_NAMES -> PartitionGroup.BOOT
            base in DATA_NAMES -> PartitionGroup.DATA
            else -> PartitionGroup.OTHER
        }
    }

    fun of(partitions: List<Partition>, group: PartitionGroup): List<Partition> =
        partitions.filter { it.group == group }

    val quickGroups: List<PartitionGroup> =
        listOf(PartitionGroup.BOOT, PartitionGroup.AVB, PartitionGroup.DYNAMIC, PartitionGroup.RECOVERY)
}

data class Partition(
    val name: String,
    val linkPath: String,
    val devicePath: String,
    val sizeBytes: Long,
    val slot: Slot,
    val group: PartitionGroup,
    val isBlockDevice: Boolean,
    val readable: Boolean,
    val isLogicalDynamic: Boolean = false
) {
    val hasKnownSize: Boolean get() = sizeBytes > 0

    val sizeLabel: String get() = Formats.humanBytes(sizeBytes)

    val baseName: String get() = Slot.stripSuffix(name)

    val imageFileName: String get() = "$name.img"

    val typeLabel: String get() = when {
        isLogicalDynamic -> "Dynamic partition (dm)"
        isBlockDevice -> "Block device"
        else -> "Link inválido"
    }
}