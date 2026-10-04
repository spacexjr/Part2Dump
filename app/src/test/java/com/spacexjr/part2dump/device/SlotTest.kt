package com.spacexjr.part2dump.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotTest {

    @Test
    fun fromSuffixAcceptsBothConventions() {
        assertEquals(Slot.A, Slot.fromSuffix("_a"))
        assertEquals(Slot.A, Slot.fromSuffix("a"))
        assertEquals(Slot.A, Slot.fromSuffix(" A "))
        assertEquals(Slot.B, Slot.fromSuffix("_b"))
        assertEquals(Slot.B, Slot.fromSuffix("B"))
        assertEquals(Slot.NONE, Slot.fromSuffix(null))
        assertEquals(Slot.NONE, Slot.fromSuffix(""))
        assertEquals(Slot.NONE, Slot.fromSuffix("unknown"))
    }

    @Test
    fun fromPartitionNameUsesUnderscoreSuffixOnly() {
        assertEquals(Slot.A, Slot.fromPartitionName("boot_a"))
        assertEquals(Slot.B, Slot.fromPartitionName("vendor_boot_b"))
        assertEquals(Slot.NONE, Slot.fromPartitionName("system"))
        assertEquals(Slot.NONE, Slot.fromPartitionName("super"))
    }

    @Test
    fun stripSuffixRemovesSlotMarkerButKeepsShortNames() {
        assertEquals("boot", Slot.stripSuffix("boot_a"))
        assertEquals("boot", Slot.stripSuffix("boot_b"))
        assertEquals("system", Slot.stripSuffix("system"))
        assertEquals("a", Slot.stripSuffix("a"))
    }

    @Test
    fun labelUsesUppercaseLetter() {
        assertEquals("A", Slot.A.label)
        assertEquals("B", Slot.B.label)
        assertEquals("-", Slot.NONE.label)
    }

    @Test
    fun detectFindsAbDeviceFromSlotSuffixProperty() {
        val state = Slot.detect(listOf("system"), mapOf("ro.boot.slot_suffix" to "_a"))
        assertTrue(state.isAbDevice)
        assertEquals(Slot.A, state.currentSlot)
        assertEquals("_a", state.suffix)
        assertNull(state.slotIndex)
    }

    @Test
    fun detectFallsBackToSlotIndexProperty() {
        val state = Slot.detect(listOf("system"), mapOf("ro.boot.slot" to "1"))
        assertTrue(state.isAbDevice)
        assertEquals(Slot.B, state.currentSlot)
        assertEquals(1, state.slotIndex)
    }

    @Test
    fun detectInfersAbDeviceFromPartitionNames() {
        val state = Slot.detect(listOf("boot_a", "boot_b", "system_a", "system_b"), emptyMap())
        assertTrue(state.isAbDevice)
        assertEquals(Slot.NONE, state.currentSlot)
    }

    @Test
    fun detectTreatsUnknownSuffixAsAbsent() {
        val state = Slot.detect(listOf("boot"), mapOf("ro.boot.slot_suffix" to "unknown"))
        assertFalse(state.isAbDevice)
        assertEquals(Slot.NONE, state.currentSlot)
    }

    @Test
    fun detectHonoursVirtualAbFlag() {
        val state = Slot.detect(listOf("system"), mapOf("ro.virtual_ab.enabled" to "1"))
        assertTrue(state.isAbDevice)
    }

    @Test
    fun detectOnNonAbDeviceHasNoSlot() {
        val state = Slot.detect(listOf("boot", "recovery", "system"), emptyMap())
        assertFalse(state.isAbDevice)
        assertEquals(Slot.NONE, state.currentSlot)
    }

    @Test
    fun filterShowsEverythingWhenDeviceIsNotAb() {
        for (filter in SlotFilter.values()) {
            assertTrue(SlotFilter.matches(filter, Slot.A, Slot.B, isAbDevice = false))
            assertTrue(SlotFilter.matches(filter, Slot.NONE, Slot.B, isAbDevice = false))
        }
    }

    @Test
    fun filterCurrentKeepsUnslottedPartitionsVisible() {
        assertTrue(SlotFilter.matches(SlotFilter.CURRENT, Slot.A, Slot.A, isAbDevice = true))
        assertFalse(SlotFilter.matches(SlotFilter.CURRENT, Slot.B, Slot.A, isAbDevice = true))
        assertTrue(SlotFilter.matches(SlotFilter.CURRENT, Slot.NONE, Slot.A, isAbDevice = true))
    }

    @Test
    fun filterSlotSelectorsKeepUnslottedPartitionsVisible() {
        assertTrue(SlotFilter.matches(SlotFilter.A, Slot.A, Slot.B, isAbDevice = true))
        assertFalse(SlotFilter.matches(SlotFilter.A, Slot.B, Slot.B, isAbDevice = true))
        assertTrue(SlotFilter.matches(SlotFilter.A, Slot.NONE, Slot.B, isAbDevice = true))
        assertTrue(SlotFilter.matches(SlotFilter.B, Slot.B, Slot.A, isAbDevice = true))
        assertFalse(SlotFilter.matches(SlotFilter.B, Slot.A, Slot.A, isAbDevice = true))
    }

    @Test
    fun filterAllAlwaysMatches() {
        assertTrue(SlotFilter.matches(SlotFilter.ALL, Slot.B, Slot.A, isAbDevice = true))
        assertTrue(SlotFilter.matches(SlotFilter.ALL, Slot.NONE, Slot.NONE, isAbDevice = true))
    }
}
