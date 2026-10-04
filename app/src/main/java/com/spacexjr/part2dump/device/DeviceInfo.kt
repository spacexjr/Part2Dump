package com.spacexjr.part2dump.device

import com.spacexjr.part2dump.root.RootStatus

data class DeviceInfo(
    val snapshot: SystemSnapshot,
    val slotState: SlotState,
    val rootStatus: RootStatus,
    val rootBinary: String?,
    val byNamePath: String?,
    val hasDynamicPartitions: Boolean,
    val superPartition: String?,
    val dynamicPartitionCount: Int
) {
    val model: String get() = snapshot.deviceModel
    val codename: String get() = snapshot.deviceCodename
    val androidRelease: String get() = snapshot.androidRelease?.takeIf { it.isNotBlank() } ?: "-"
    val sdkInt: Int get() = snapshot.sdkInt
    val abi: String get() = snapshot.abi
    val slotLabel: String get() = slotState.currentLabel
    val superName: String get() = superPartition?.takeIf { it.isNotBlank() } ?: "-"
}