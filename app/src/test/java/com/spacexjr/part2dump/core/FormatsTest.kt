package com.spacexjr.part2dump.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FormatsTest {

    @Test
    fun humanBytesUsesBinaryUnits() {
        assertEquals("0 B", Formats.humanBytes(0))
        assertEquals("512 B", Formats.humanBytes(512))
        assertEquals("1.0 KiB", Formats.humanBytes(1024))
        assertEquals("1.5 MiB", Formats.humanBytes(1024L * 1024 * 3 / 2))
        assertEquals("1.0 GiB", Formats.humanBytes(1024L * 1024 * 1024))
    }

    @Test
    fun humanBytesDropsDecimalsAboveHundred() {
        assertEquals("100 MiB", Formats.humanBytes(100L * 1024 * 1024))
        assertEquals("99.5 MiB", Formats.humanBytes(99L * 1024 * 1024 + 512 * 1024))
    }

    @Test
    fun humanBytesIsUnknownForNegativeInput() {
        assertEquals("?", Formats.humanBytes(-1))
        assertEquals("?", Formats.humanBytes(Long.MIN_VALUE))
    }

    @Test
    fun humanRateAppendsUnitSuffix() {
        assertEquals("1.0 MiB/s", Formats.humanRate(1024L * 1024))
    }

    @Test
    fun percentOfClampsToHundredAndHandlesInvalidTotals() {
        assertEquals(0, Formats.percentOf(0, 100))
        assertEquals(0, Formats.percentOf(50, 0))
        assertEquals(0, Formats.percentOf(50, -10))
        assertEquals(50, Formats.percentOf(50, 100))
        assertEquals(100, Formats.percentOf(200, 100))
    }

    @Test
    fun shortHashKeepsShortValuesAndElidesLongOnes() {
        assertEquals("abc", Formats.shortHash("abc"))
        val full = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("01234567...89abcdef", Formats.shortHash(full))
    }

    @Test
    fun sanitizeNameStripsUnsafeCharacters() {
        assertEquals("Pixel_7_pro", Formats.sanitizeName("Pixel 7 pro"))
        assertEquals("a_b", Formats.sanitizeName("a///b"))
        assertEquals("dev", Formats.sanitizeName("../../dev", fallback = "dev"))
        assertEquals("device", Formats.sanitizeName(null))
        assertEquals("device", Formats.sanitizeName("   "))
        assertEquals("device", Formats.sanitizeName("***"))
    }

    @Test
    fun sanitizeNameLimitsLength() {
        val result = Formats.sanitizeName("a".repeat(200))
        assertEquals(48, result.length)
        assertTrue(result.all { it == 'a' })
    }

    @Test
    fun folderTimestampIsStable() {
        val previousTimeZone = TimeZone.getDefault()
        val previousLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals("20231114-221320", Formats.timestampForFolder(Date(1_700_000_000_000L)))
        } finally {
            TimeZone.setDefault(previousTimeZone)
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun isoTimestampIsAlwaysUtc() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals("2023-11-14T22:13:20Z", Formats.isoTimestamp(Date(1_700_000_000_000L)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun oneLineCollapsesNewlinesAndFallsBackToDash() {
        assertEquals("-", Formats.oneLine(null))
        assertEquals("-", Formats.oneLine("   \n  "))
        assertEquals("a b c", Formats.oneLine("a\nb\r\nc"))
        assertEquals("a b", Formats.oneLine("a\r\n\r\nb"))
        assertEquals("a b", Formats.oneLine("a \t\t b"))
    }

    @Test
    fun durationUsesMinutesAndSecondsOrFractionalSeconds() {
        assertEquals("-", Formats.duration(-5))
        assertEquals("0.0s", Formats.duration(0))
        assertEquals("1.5s", Formats.duration(1500))
        assertEquals("2m 05s", Formats.duration(125_000))
        assertEquals("60m 00s", Formats.duration(3_600_000))
    }
}
