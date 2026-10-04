package com.spacexjr.part2dump.partition

import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.root.ShellException
import java.io.File

data class DynamicScan(
    val hasDynamicPartitions: Boolean,
    val superPartition: String?,
    val logicalNames: List<String>,
    val devices: Map<String, String>
)

object DynamicPartitionDetector {

    private const val TIMEOUT_MS = 20_000L

    private const val MOUNTS_MARKER = "--MOUNTS--"
    private const val LPDUMP_MARKER = "--LPDUMP--"
    private const val PROPS_MARKER = "--PROPS--"

    private val SCRIPT = """
echo '$MOUNTS_MARKER'
cat /proc/mounts 2>/dev/null
echo '$LPDUMP_MARKER'
lpdump 2>/dev/null
echo '$PROPS_MARKER'
getprop ro.boot.super_partition 2>/dev/null
getprop ro.boot.dynamic_partitions 2>/dev/null
""".trimIndent()

    private val LPDUMP_NAME = Regex("Partition \"([^\"]+)\"")

    private val KNOWN_DYNAMIC = setOf(
        "system",
        "system_ext",
        "system_dlkm",
        "product",
        "vendor",
        "odm",
        "oem"
    )

    fun detect(shell: Shell): DynamicScan {
        val raw = try {
            shell.exec(SCRIPT, TIMEOUT_MS).stdout
        } catch (error: ShellException) {
            P2DLog.w("Falha ao consultar partições dinâmicas", error)
            return DynamicScan(false, null, emptyList(), emptyMap())
        }

        val devices = LinkedHashMap<String, String>()
        section(raw, MOUNTS_MARKER, LPDUMP_MARKER).lineSequence().forEach { line ->
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < 2) return@forEach
            val device = fields[0].trim()
            val mountPoint = fields[1].trim()
            if (!device.startsWith("/dev/")) return@forEach
            val name = mountPoint.trim('/').substringBefore('/')
            if (name !in KNOWN_DYNAMIC) return@forEach
            if (!devices.containsKey(name)) devices[name] = device
        }

        val lpdumpNames = LPDUMP_NAME.findAll(section(raw, LPDUMP_MARKER, PROPS_MARKER))
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lpdumpNames.isNotEmpty()) {
            P2DLog.i("lpdump reportou: ${lpdumpNames.joinToString()}")
        }

        val propLines = section(raw, PROPS_MARKER, null).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val superFromProp = propLines.getOrNull(0)?.takeIf { it.isNotBlank() }
        val dynamicFlag = propLines.getOrNull(1)?.trim()

        val names = LinkedHashSet<String>()
        names.addAll(devices.keys)
        names.addAll(lpdumpNames.filter { it in KNOWN_DYNAMIC })
        for (name in names) {
            if (devices.containsKey(name)) continue
            resolveDeviceNode(name)?.let { devices[name] = it }
        }

        val hasDynamic = dynamicFlag == "1" ||
            superFromProp != null ||
            lpdumpNames.isNotEmpty() ||
            devices.isNotEmpty()
        P2DLog.i("Dynamic: has=$hasDynamic super=$superFromProp devices=${devices.keys.joinToString()}")
        return DynamicScan(hasDynamic, superFromProp, names.toList(), devices)
    }

    private fun resolveDeviceNode(name: String): String? {
        val candidates = listOf("/dev/block/mapper/$name", "/dev/block/$name")
        for (candidate in candidates) {
            if (File(candidate).exists()) return candidate
        }
        return null
    }

    private fun section(raw: String, startMarker: String, endMarker: String?): String {
        val start = raw.indexOf(startMarker)
        if (start < 0) return ""
        val from = start + startMarker.length
        val to = if (endMarker == null) {
            raw.length
        } else {
            val index = raw.indexOf(endMarker, from)
            if (index < 0) raw.length else index
        }
        return raw.substring(from, to)
    }

    private val WHITESPACE = Regex("\\s+")
}