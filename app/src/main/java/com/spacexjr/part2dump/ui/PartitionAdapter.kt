package com.spacexjr.part2dump.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.device.Slot
import com.spacexjr.part2dump.partition.Partition

class PartitionAdapter(
    context: Context,
    private val onToggle: (Partition, Boolean) -> Unit,
    private val onLongPress: (Partition) -> Boolean
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val contextRef: Context = context.applicationContext

    private var items: List<Partition> = emptyList()
    private var selected: Set<String> = emptySet()
    private var interactive: Boolean = true

    fun submit(newItems: List<Partition>, newSelected: Set<String>, isInteractive: Boolean) {
        items = newItems
        selected = newSelected
        interactive = isInteractive
        notifyDataSetChanged()
    }

    fun updateSelection(newSelected: Set<String>) {
        selected = newSelected
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Partition? = items.getOrNull(position)

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Partition? = items.getOrNull(position)

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_partition, parent, false)
        val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
        val partition = items[position]
        val context = contextRef

        holder.name.text = partition.name
        holder.meta.text = buildString {
            append(partition.sizeLabel)
            append(" • ")
            append(if (partition.hasKnownSize) partition.typeLabel else "tamanho desconhecido")
            if (partition.slot != Slot.NONE) {
                append(" • slot ")
                append(partition.slot.label)
            }
        }
        holder.path.text = partition.devicePath

        holder.badge.visibility = View.GONE
        if (!partition.readable) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = context.getString(R.string.detail_no)
        }

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = selected.contains(partition.name)
        holder.check.isEnabled = interactive
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (!interactive) {
                holder.check.isChecked = selected.contains(partition.name)
                return@setOnCheckedChangeListener
            }
            onToggle(partition, checked)
        }

        holder.itemView.isEnabled = interactive
        holder.itemView.alpha = if (interactive) 1f else DISABLED_ALPHA
        holder.itemView.setOnClickListener {
            if (!interactive) return@setOnClickListener
            onToggle(partition, !selected.contains(partition.name))
        }
        holder.itemView.setOnLongClickListener {
            onLongPress(partition)
        }
        return view
    }

    private class Holder(root: View) {
        val itemView: View = root
        val check: CheckBox = root.findViewById(R.id.partitionCheck)
        val name: TextView = root.findViewById(R.id.partitionName)
        val meta: TextView = root.findViewById(R.id.partitionMeta)
        val path: TextView = root.findViewById(R.id.partitionPath)
        val badge: TextView = root.findViewById(R.id.partitionBadge)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.55f
    }
}