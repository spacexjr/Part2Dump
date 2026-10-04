package com.spacexjr.part2dump.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Formats {

    private val BYTE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    private val SAFE_NAME = Regex("[^A-Za-z0-9._-]+")
    private val WHITESPACE_RUN = Regex("\\s+")

    fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < BYTE_UNITS.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        val pattern = if (value >= 100.0) "%.0f" else "%.1f"
        return String.format(Locale.US, pattern, value) + " " + BYTE_UNITS[unitIndex]
    }

    fun humanRate(bytesPerSecond: Long): String = humanBytes(bytesPerSecond) + "/s"

    fun percentOf(done: Long, total: Long): Int {
        if (total <= 0L || done <= 0L) return 0
        val raw = (done.toDouble() / total.toDouble() * 100.0).toInt()
        return raw.coerceIn(0, 100)
    }

    fun shortHash(hash: String): String {
        if (hash.length <= 20) return hash
        return hash.take(8) + "..." + hash.takeLast(8)
    }

    fun sanitizeName(raw: String?, fallback: String = "device"): String {
        val cleaned = SAFE_NAME.replace(raw.orEmpty(), "_").replace("_+", "_").trim('_', '.', '-')
        val limited = if (cleaned.length > 48) cleaned.substring(0, 48).trim('_', '.', '-') else cleaned
        return limited.ifBlank { fallback }
    }

    fun timestampForFolder(date: Date = Date()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date)

    fun isoTimestamp(date: Date = Date()): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }

    fun oneLine(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "-"
        return WHITESPACE_RUN.replace(trimmed, " ")
    }

    fun duration(millis: Long): String {
        if (millis < 0) return "-"
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else String.format(Locale.US, "%.1fs", millis / 1000.0)
    }
}