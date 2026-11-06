package app.wuad.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.wuad.databinding.ItemRuleBinding

data class RuleRow(
    val kind: String,
    val id: String,
    val title: String,
    val sub: String,
    val on: Boolean,
    val preset: Boolean,
    val folder: Boolean = false,
    val name: String = ""
)

class RuleAdapter : RecyclerView.Adapter<RuleAdapter.VH>() {
    private var rows: List<RuleRow> = emptyList()
    var onEdit: (Bundle) -> Unit = {}
    var onDelete: (String, String) -> Unit = { _, _ -> }
    var onToggle: (String, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> }
    var onOpen: (String, String) -> Unit = { _, _ -> }

    fun submit(v: List<RuleRow>) {
        rows = v
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.b.title.text = if (row.folder) row.title + "  ›" else row.title
        holder.b.sub.text = row.sub
        holder.b.toggle.visibility = if (row.kind == "dns") View.GONE else View.VISIBLE
        holder.b.toggle.setOnCheckedChangeListener(null)
        holder.b.toggle.isChecked = row.on
        holder.b.toggle.setOnCheckedChangeListener { _, on -> onToggle(row.kind, row.id, on, row.folder) }
        holder.b.edit.visibility = if (row.folder) View.GONE else View.VISIBLE
        holder.b.delete.visibility = if (row.folder) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener {
            if (row.folder) onOpen(row.id, row.name.ifBlank { row.title })
        }
        holder.b.edit.setOnClickListener {
            val extras = Bundle()
            extras.putString("kind", row.kind)
            extras.putString("id", row.id)
            extras.putString("title", row.title)
            extras.putString("sub", row.sub)
            onEdit(extras)
        }
        holder.b.delete.setOnClickListener { onDelete(row.kind, row.id) }
    }

    override fun getItemCount() = rows.size
    class VH(val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root)
}
