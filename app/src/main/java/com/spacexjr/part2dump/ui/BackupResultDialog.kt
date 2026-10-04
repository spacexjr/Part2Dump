package com.spacexjr.part2dump.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.checksum.ManifestEntry
import com.spacexjr.part2dump.checksum.ManifestWriter
import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.dump.BackupResult
import com.spacexjr.part2dump.storage.StorageManager

object BackupResultDialog {

    fun show(activity: Activity, result: BackupResult) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_backup_result, null, false)
        val title = view.findViewById<TextView>(R.id.resultTitle)
        val folder = view.findViewById<TextView>(R.id.resultFolder)
        val summary = view.findViewById<TextView>(R.id.resultSummary)
        val entries = view.findViewById<LinearLayout>(R.id.resultEntries)

        title.setText(
            when {
                result.cancelled -> R.string.result_title_cancelled
                result.success -> R.string.result_title_success
                else -> R.string.result_title_failed
            }
        )

        val directory = result.backupDir?.absolutePath
        folder.text = if (directory == null) {
            Formats.oneLine(result.detail)
        } else {
            activity.getString(
                R.string.result_folder,
                directory,
                ManifestWriter.MANIFEST_NAME
            )
        }

        val done = result.entries.count { it.status == ManifestEntry.STATUS_OK }
        val bytes = result.entries
            .filter { it.status == ManifestEntry.STATUS_OK }
            .sumOf { if (it.sizeBytes > 0) it.sizeBytes else 0L }
        val summaryText = activity.resources.getQuantityString(
            R.plurals.result_summary,
            done,
            done,
            result.entries.size,
            Formats.humanBytes(bytes)
        ) + "\n" + activity.getString(R.string.result_legacy_folder, StorageManager.LEGACY_DIR_NAME)
        summary.text = summaryText

        for (entry in result.entries) {
            entries.addView(buildEntry(activity, entry))
        }

        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun buildEntry(activity: Activity, entry: ManifestEntry): View {
        val row = activity.layoutInflater.inflate(R.layout.item_manifest_entry, null, false)
        val name = row.findViewById<TextView>(R.id.entryName)
        val meta = row.findViewById<TextView>(R.id.entryMeta)
        val hash = row.findViewById<TextView>(R.id.entryHash)

        val ok = entry.status == ManifestEntry.STATUS_OK
        name.text = entry.name
        meta.text = if (ok) {
            activity.getString(
                R.string.result_entry_ok,
                Formats.humanBytes(entry.sizeBytes),
                Formats.duration(entry.durationMs),
                entry.slot
            )
        } else {
            activity.getString(
                R.string.result_entry_failed,
                Formats.oneLine(entry.error),
                Formats.duration(entry.durationMs)
            )
        }
        hash.text = if (!entry.sha256.isNullOrBlank()) {
            activity.getString(R.string.label_sha256, Formats.shortHash(entry.sha256.orEmpty()))
        } else {
            activity.getString(R.string.result_hash_unavailable)
        }
        return row
    }
}