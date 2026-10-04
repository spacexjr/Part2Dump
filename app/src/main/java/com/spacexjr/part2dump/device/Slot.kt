package com.spacexjr.part2dump.device

import java.util.Locale

enum class Slot(val suffix: String) {
    A("_a"),
    B("_b"),
    NONE("");

    val label: String get() = if (this == NONE) "-" else suffix.removePrefix("_").uppercase(Locale.US)

    companion object {
        fun fromSuffix(raw: String?): Slot {
            val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return when {
                value == "_a" || value == "a" -> A
                value == "_b" || value == "b" -> B
                else -> NONE
            }
        }

        fun fromPartitionName(name: String): Slot {
            val value = name.trim()
            return when {
                value.endsWith("_a", ignoreCase = true) -> A
                value.endsWith("_b", ignoreCase = true) -> B
                else -> NONE
            }
        }

        fun stripSuffix(name: String): String {
            val value = name.trim()
            return when {
                value.endsWith("_a", ignoreCase = true) && value.length > 2 -> value.dropLast(2)
                value.endsWith("_b", ignoreCase = true) && value.length > 2 -> value.dropLast(2)
                else -> value
            }
        }

        fun detect(names: Collection<String>, props: Map<String, String>): SlotState {
            val suffix = props["ro.boot.slot_suffix"]?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }
            val slotIndex = props["ro.boot.slot"]?.trim()?.toIntOrNull()
            val hasA = names.any { it.endsWith("_a", ignoreCase = true) }
            val hasB = names.any { it.endsWith("_b", ignoreCase = true) }
            val virtualAb = props["ro.virtual_ab.enabled"]?.trim() == "1"
            val isAb = suffix != null || slotIndex != null || (hasA && hasB) || virtualAb
            val current = fromSuffix(suffix).takeIf { it != Slot.NONE } ?: when (slotIndex) {
                0 -> A
                1 -> B
                else -> NONE
            }
            return SlotState(isAbDevice = isAb, currentSlot = current, suffix = suffix, slotIndex = slotIndex)
        }
    }
}

data class SlotState(
    val isAbDevice: Boolean,
    val currentSlot: Slot,
    val suffix: String?,
    val slotIndex: Int?
) {
    val currentLabel: String get() = currentSlot.label
}

enum class SlotFilter {
    CURRENT,
    A,
    B,
    ALL;

    companion object {
        fun matches(filter: SlotFilter, slot: Slot, current: Slot, isAbDevice: Boolean): Boolean {
            if (!isAbDevice || filter == ALL) return true
            return when (filter) {
                CURRENT -> slot == Slot.NONE || slot == current
                A -> slot == Slot.NONE || slot == Slot.A
                B -> slot == Slot.NONE || slot == Slot.B
                ALL -> true
            }
        }
    }
}