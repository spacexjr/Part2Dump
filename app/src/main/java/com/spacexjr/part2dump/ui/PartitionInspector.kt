package com.spacexjr.part2dump.ui

import android.app.Activity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.device.Slot
import com.spacexjr.part2dump.partition.Partition

object PartitionInspector {

    fun show(
        activity: Activity,
        partition: Partition,
        onDump: (Partition) -> Unit,
        onSha256: (Partition) -> Unit
    ): AlertDialog {
        val view = activity.layoutInflater.inflate(R.layout.dialog_partition_detail, null, false)
        val name = view.findViewById<TextView>(R.id.detailName)
        val path = view.findViewById<TextView>(R.id.detailPath)
        val device = view.findViewById<TextView>(R.id.detailDevice)
        val size = view.findViewById<TextView>(R.id.detailSize)
        val slot = view.findViewById<TextView>(R.id.detailSlot)
        val group = view.findViewById<TextView>(R.id.detailGroup)
        val type = view.findViewById<TextView>(R.id.detailType)
        val readable = view.findViewById<TextView>(R.id.detailReadable)

        name.text = partition.name
        path.text = activity.getString(R.string.label_path, partition.linkPath)
        device.text = activity.getString(R.string.label_device, partition.devicePath)
        size.text = activity.getString(R.string.label_size, partition.sizeLabel)
        slot.text = activity.getString(R.string.label_slot, slotLabel(activity, partition))
        group.text = activity.getString(R.string.label_group, partition.group.label)
        type.text = activity.getString(R.string.label_type, partition.typeLabel)
        readable.text = activity.getString(R.string.label_readable, yesNo(activity, partition.readable))

        val builder = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_dump) { _, _ -> onDump(partition) }
            .setPositiveButton(R.string.action_sha256) { _, _ -> onSha256(partition) }

        return builder.create()
    }

    fun setResult(dialog: AlertDialog?, text: String?) {
        val view = dialog?.findViewById<View>(R.id.detailResult) ?: return
        val label = view as? TextView ?: return
        if (text.isNullOrBlank()) {
            label.visibility = View.GONE
            label.text = ""
        } else {
            label.visibility = View.VISIBLE
            label.text = text
        }
    }

    fun setProgress(dialog: AlertDialog?, percent: Int?, indeterminate: Boolean) {
        val view = dialog?.findViewById<View>(R.id.detailProgress) ?: return
        val bar = view as? ProgressBar ?: return
        if (percent == null) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        if (indeterminate) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = percent.coerceIn(0, 100)
        }
    }

    private fun slotLabel(activity: Activity, partition: Partition): String =
        if (partition.slot == Slot.NONE) {
            activity.getString(R.string.detail_slot_none)
        } else {
            partition.slot.label
        }

    private fun yesNo(activity: Activity, value: Boolean): String =
        activity.getString(if (value) R.string.detail_yes else R.string.detail_no)
}