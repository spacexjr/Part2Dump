package com.spacexjr.part2dump.storage

import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.partition.Partition
import java.io.File

object BackupLayout {

    fun folderName(deviceLabel: String?, timestamp: String): String =
        Formats.sanitizeName(deviceLabel, "device") + "_" + timestamp

    fun createBackupDirectory(
        storage: StorageManager,
        deviceLabel: String?,
        timestamp: String
    ): File? {
        val root = storage.backupRoot()
        if (!storage.ensureDirectory(root)) return null
        val base = File(root, folderName(deviceLabel, timestamp))
        var candidate = base
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, base.name + "_" + suffix)
            suffix++
        }
        if (!storage.ensureDirectory(candidate)) return null
        return candidate
    }

    fun imageFileName(partition: Partition): String = partition.imageFileName
}