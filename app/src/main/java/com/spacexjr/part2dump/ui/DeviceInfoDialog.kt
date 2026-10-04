package com.spacexjr.part2dump.ui

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.device.DeviceInfo
import com.spacexjr.part2dump.device.Slot

object DeviceInfoDialog {

    fun show(activity: Activity, info: DeviceInfo) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_device_info, null, false)
        val header = view.findViewById<TextView>(R.id.infoHeader)
        val table = view.findViewById<TableLayout>(R.id.infoTable)

        header.text = activity.getString(
            R.string.device_header,
            Formats.oneLine(info.model),
            info.androidRelease,
            info.sdkInt
        )

        addRow(activity, table, R.string.device_manufacturer, Formats.oneLine(info.snapshot.manufacturer))
        addRow(activity, table, R.string.device_model, Formats.oneLine(info.snapshot.model))
        addRow(activity, table, R.string.device_codename, Formats.oneLine(info.codename))
        addRow(activity, table, R.string.device_android, Formats.oneLine(info.androidRelease))
        addRow(activity, table, R.string.device_sdk, info.sdkInt.toString())
        addRow(activity, table, R.string.device_build, Formats.oneLine(info.snapshot.buildId))
        addRow(
            activity,
            table,
            R.string.device_abi,
            info.snapshot.abis.joinToString(", ").ifBlank { Formats.oneLine(info.abi) }
        )
        addRow(activity, table, R.string.device_kernel, Formats.oneLine(info.snapshot.kernelVersion))
        addRow(activity, table, R.string.device_bootloader, Formats.oneLine(info.snapshot.bootloader))
        addRow(
            activity,
            table,
            R.string.device_slot,
            activity.getString(
                R.string.device_ab_device,
                if (info.slotState.isAbDevice) activity.getString(R.string.device_yes)
                else activity.getString(R.string.device_no),
                if (info.slotState.currentSlot == Slot.NONE) Formats.oneLine(info.slotState.suffix)
                else info.slotState.currentLabel
            )
        )
        addRow(activity, table, R.string.device_root, activity.getString(UserMessages.forRoot(info.rootStatus)))
        addRow(activity, table, R.string.device_su, Formats.oneLine(info.rootBinary))
        addRow(
            activity,
            table,
            R.string.device_dynamic,
            if (info.hasDynamicPartitions) activity.getString(R.string.device_yes)
            else activity.getString(R.string.device_no)
        )
        addRow(activity, table, R.string.device_super, Formats.oneLine(info.superName))
        addRow(activity, table, R.string.device_by_name, Formats.oneLine(info.byNamePath))
        addRow(
            activity,
            table,
            R.string.device_fingerprint,
            Formats.oneLine(info.snapshot.fingerprint)
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.device_info_title)
            .setView(view)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun addRow(activity: Activity, table: TableLayout, labelRes: Int, value: String) {
        val row = TableRow(activity)
        val label = TextView(activity)
        label.text = activity.getString(labelRes)
        label.textSize = 13f
        label.setPadding(0, PADDING, PADDING, PADDING)
        label.setTextColor(0xFF888888.toInt())

        val text = TextView(activity)
        text.text = value
        text.textSize = 13f
        text.setPadding(0, PADDING, 0, PADDING)
        text.gravity = Gravity.END

        row.addView(label, TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f))
        row.addView(text, TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f))
        table.addView(row)
    }

    private const val PADDING = 8
}