package com.spacexjr.part2dump.checksum

import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.device.DeviceInfo
import com.spacexjr.part2dump.root.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ManifestEntry(
    val name: String,
    val linkPath: String,
    val devicePath: String,
    val sizeBytes: Long,
    val slot: String,
    val group: String,
    val sha256: String?,
    val durationMs: Long,
    val status: String,
    val error: String? = null
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
    }
}

class ManifestWriter {

    fun build(
        entries: List<ManifestEntry>,
        device: DeviceInfo,
        backupFolder: String,
        toolVersion: String,
        timestampIso: String = Formats.isoTimestamp()
    ): String {
        val root = JSONObject()
        root.put("tool", "Part2Dump")
        root.put("tool_version", toolVersion)
        root.put("timestamp", timestampIso)
        root.put("backup_folder", backupFolder)
        root.put("device", device.model)
        root.put("device_codename", device.codename)
        root.put("manufacturer", device.snapshot.manufacturer ?: JSONObject.NULL)
        root.put("android", device.androidRelease)
        root.put("sdk", device.sdkInt)
        root.put("build_id", device.snapshot.buildId ?: JSONObject.NULL)
        root.put("fingerprint", device.snapshot.fingerprint ?: JSONObject.NULL)
        root.put("abi", device.abi)
        root.put("slot", device.slotState.currentSlot.suffix.ifBlank { "none" })
        root.put("ab_device", device.slotState.isAbDevice)
        root.put("dynamic_partitions", device.hasDynamicPartitions)
        root.put("super_partition", device.superPartition ?: JSONObject.NULL)
        root.put("block_device_dir", device.byNamePath ?: JSONObject.NULL)
        root.put("root_status", device.rootStatus.name)
        root.put("root_binary", device.rootBinary ?: JSONObject.NULL)

        val array = JSONArray()
        for (entry in entries) {
            val item = JSONObject()
            item.put("name", entry.name)
            item.put("path", entry.linkPath)
            item.put("device", entry.devicePath)
            item.put("size", entry.sizeBytes)
            item.put("slot", entry.slot)
            item.put("group", entry.group)
            item.put("duration_ms", entry.durationMs)
            item.put("status", entry.status)
            if (!entry.sha256.isNullOrBlank()) item.put("sha256", entry.sha256)
            if (!entry.error.isNullOrBlank()) item.put("notice", entry.error)
            array.put(item)
        }
        root.put("partitions", array)

        val totals = JSONObject()
        totals.put("requested", entries.size)
        totals.put("completed", entries.count { it.status == ManifestEntry.STATUS_OK })
        totals.put("failed", entries.count { it.status != ManifestEntry.STATUS_OK })
        totals.put(
            "bytes",
            entries.filter { it.status == ManifestEntry.STATUS_OK }.sumOf { if (it.sizeBytes > 0) it.sizeBytes else 0L }
        )
        root.put("totals", totals)

        return root.toString(2)
    }

    fun write(directory: File, json: String, shell: Shell?): Boolean {
        val target = File(directory, MANIFEST_NAME)
        val writtenByApp = try {
            target.writeText(json)
            true
        } catch (error: Exception) {
            P2DLog.w("Escrita do manifest pelo app falhou: ${error.message}")
            false
        }
        if (writtenByApp) return true
        if (shell == null) return false
        return try {
            val command = "printf '%s' " + Shell.quote(json) + " > " + Shell.quote(target.absolutePath)
            val result = shell.exec(command, WRITE_TIMEOUT_MS)
            if (result.isSuccess) {
                shell.exec("chown " + APP_UID + ":" + APP_UID + " " + Shell.quote(target.absolutePath) + " 2>/dev/null")
                true
            } else {
                P2DLog.e("Escrita do manifest via root falhou: ${result.stderr.trim()}")
                false
            }
        } catch (error: Exception) {
            P2DLog.e("Escrita do manifest via root lançou exceção", error)
            false
        }
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        private const val WRITE_TIMEOUT_MS = 60_000L
        private val APP_UID: Int get() = android.os.Process.myUid()
    }
}