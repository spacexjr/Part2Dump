package com.spacexjr.part2dump.partition

import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.device.Slot
import com.spacexjr.part2dump.device.SlotFilter
import com.spacexjr.part2dump.device.SlotState
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.root.ShellException

data class PartitionScan(
    val partitions: List<Partition>,
    val dynamicPartitions: List<Partition>,
    val byName: ByNameDetection,
    val slotState: SlotState,
    val hasDynamicPartitions: Boolean,
    val superPartition: String?
) {
    val all: List<Partition> get() = partitions + dynamicPartitions
}

class PartitionRepository(private val shell: Shell) {

    fun scan(props: Map<String, String>): PartitionScan {
        val detection = PartitionPathDetector.detect(shell)
        val directory = detection.directory
        if (directory == null) {
            return PartitionScan(
                partitions = emptyList(),
                dynamicPartitions = emptyList(),
                byName = detection,
                slotState = Slot.detect(emptyList(), props),
                hasDynamicPartitions = false,
                superPartition = null
            )
        }

        val entries = listEntries(directory)
        val dynamic = DynamicPartitionDetector.detect(shell)
        val dynamicPaths = dynamic.devices.values.filter { it.isNotBlank() }
        val probed = DeviceProbe.probe(
            shell,
            (entries.map { it.realPath } + dynamicPaths).filter { it.isNotBlank() }.distinct()
        )

        val rawPartitions = entries.map { entry ->
            val info = probed[entry.realPath]
            Partition(
                name = entry.name,
                linkPath = entry.linkPath,
                devicePath = entry.realPath,
                sizeBytes = info?.sizeBytes ?: -1L,
                slot = Slot.NONE,
                group = PartitionGroups.classify(entry.name),
                isBlockDevice = info?.isBlockDevice ?: false,
                readable = info?.readable ?: false
            )
        }

        val knownNames = rawPartitions.map { it.name }.toSet()
        val slotState = Slot.detect(rawPartitions.map { it.name }, props)

        val partitions = rawPartitions
            .map { it.copy(slot = if (slotState.isAbDevice) Slot.fromPartitionName(it.name) else Slot.NONE) }
            .sortedWith(DISPLAY_ORDER)

        val dynamicPartitions = dynamic.devices.entries
            .filter { !knownNames.contains(it.key) }
            .map { entry ->
                val info = probed[entry.value]
                Partition(
                    name = entry.key,
                    linkPath = entry.value,
                    devicePath = entry.value,
                    sizeBytes = info?.sizeBytes ?: -1L,
                    slot = Slot.NONE,
                    group = PartitionGroup.DYNAMIC,
                    isBlockDevice = info?.isBlockDevice ?: true,
                    readable = info?.readable ?: false,
                    isLogicalDynamic = true
                )
            }
            .sortedWith(DISPLAY_ORDER)

        val superFromByName = partitions.firstOrNull {
            Slot.stripSuffix(it.name).equals("super", ignoreCase = true)
        }?.name

        P2DLog.i(
            "Scan concluído: ${partitions.size} block devices, " +
                "${dynamicPartitions.size} dinâmicas, A/B=${slotState.isAbDevice}"
        )

        return PartitionScan(
            partitions = partitions,
            dynamicPartitions = dynamicPartitions,
            byName = detection,
            slotState = slotState,
            hasDynamicPartitions = dynamic.hasDynamicPartitions || superFromByName != null,
            superPartition = superFromByName ?: dynamic.superPartition
        )
    }

    fun visible(partitions: List<Partition>, filter: SlotFilter, state: SlotState): List<Partition> =
        partitions.filter { SlotFilter.matches(filter, it.slot, state.currentSlot, state.isAbDevice) }

    private fun listEntries(directory: String): List<Entry> {
        val script = String.format(LIST_SCRIPT, Shell.quote(directory))
        val output = try {
            shell.exec(script, LIST_TIMEOUT_MS).stdout
        } catch (error: ShellException) {
            P2DLog.e("Falha ao listar $directory", error)
            return emptyList()
        }
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { line ->
            val parts = line.trim().split('|')
            if (parts.size < 3) return@forEach
            val name = parts[0].trim()
            val linkPath = parts[1].trim()
            val realPath = parts[2].trim()
            if (name.isEmpty() || linkPath.isEmpty()) return@forEach
            entries.add(Entry(name, linkPath, realPath.ifBlank { linkPath }))
        }
        P2DLog.i("Listagem de $directory: ${entries.size} entradas")
        return entries
    }

    private data class Entry(val name: String, val linkPath: String, val realPath: String)

    companion object {
        private const val LIST_TIMEOUT_MS = 45_000L

        private val DISPLAY_ORDER = Comparator<Partition> { left, right ->
            val byGroup = left.group.ordinal.compareTo(right.group.ordinal)
            if (byGroup != 0) byGroup else left.name.compareTo(right.name, ignoreCase = true)
        }

        private val LIST_SCRIPT = """
for f in %s/*; do
  [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
  name=$(basename "${'$'}f")
  real=$(readlink -f "${'$'}f" 2>/dev/null)
  [ -n "${'$'}real" ] || real="${'$'}f"
  echo "${'$'}name|${'$'}f|${'$'}real"
done
""".trimIndent()
    }
}