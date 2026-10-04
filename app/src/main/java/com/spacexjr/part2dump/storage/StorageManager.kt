package com.spacexjr.part2dump.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.spacexjr.part2dump.core.P2DLog
import java.io.File
import java.io.FileOutputStream

data class SpaceReport(
    val requiredBytes: Long,
    val availableBytes: Long,
    val unknownSizes: Int
) {
    val isEnough: Boolean get() = unknownSizes > 0 || requiredBytes <= availableBytes

    val missingBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0L)
}

class StorageManager(context: Context) {

    private val appContext: Context = context.applicationContext

    fun externalRoot(): File {
        val state = try {
            Environment.getExternalStorageState()
        } catch (error: Exception) {
            P2DLog.w("Falha ao consultar estado do armazenamento", error)
            null
        }
        if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
            @Suppress("DEPRECATION")
            val legacy = Environment.getExternalStorageDirectory()
            if (legacy != null && legacy.isDirectory) return legacy
        }
        return appContext.getExternalFilesDir(null) ?: appContext.filesDir
    }

    fun backupRoot(): File = File(externalRoot(), ROOT_DIR_NAME)

    fun totalBytes(): Long = statFs()?.totalBytes ?: -1L

    fun freeBytes(): Long = statFs()?.availableBytes ?: -1L

    fun ensureDirectory(dir: File): Boolean {
        if (dir.isDirectory) return true
        val created = try {
            dir.mkdirs()
        } catch (error: Exception) {
            P2DLog.e("Falha ao criar diretório ${dir.absolutePath}", error)
            false
        }
        if (!created && !dir.isDirectory) {
            P2DLog.e("Diretório não criado: ${dir.absolutePath}")
            return false
        }
        return dir.isDirectory
    }

    fun probeWritable(dir: File): Boolean {
        if (!ensureDirectory(dir)) return false
        val probe = File(dir, WRITE_PROBE_NAME)
        return try {
            if (probe.exists()) probe.delete()
            FileOutputStream(probe).use { stream ->
                stream.write(byteArrayOf(0x50, 0x32, 0x44))
                stream.flush()
            }
            probe.delete()
            true
        } catch (error: Exception) {
            P2DLog.w("Diretório somente leitura: ${dir.absolutePath}")
            false
        }
    }

    private fun statFs(): StatFs? {
        val candidates = listOfNotNull(
            appContext.getExternalFilesDir(null),
            externalRoot()
        )
        for (candidate in candidates) {
            try {
                val stat = StatFs(candidate.absolutePath)
                if (stat.availableBytes > 0L) return stat
            } catch (error: Exception) {
                P2DLog.w("StatFs falhou em ${candidate.absolutePath}")
            }
        }
        return null
    }

    companion object {
        const val ROOT_DIR_NAME = "backups"
        const val LEGACY_DIR_NAME = "Backup"
        private const val WRITE_PROBE_NAME = ".part2dump-write-test"

        fun evaluate(requiredBytes: Long, availableBytes: Long, unknownSizes: Int): SpaceReport =
            SpaceReport(requiredBytes, availableBytes, unknownSizes)

        fun apiLevel(): Int = Build.VERSION.SDK_INT
    }
}