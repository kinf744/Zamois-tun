package com.kighmu.vpn.ui.adapters

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.profiles.HttpProxyProfile

class HttpProxyProfileAdapter(
    private var profiles: MutableList<HttpProxyProfile>,
    private val onSelectionChanged: (String, Boolean) -> Unit,
    private val onEdit: (HttpProxyProfile) -> Unit,
    private val onDelete: (HttpProxyProfile) -> Unit,
    private val onClone: (HttpProxyProfile) -> Unit
) : RecyclerView.Adapter<HttpProxyProfileAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val checkbox: CheckBox = v.findViewById(android.R.id.checkbox)
        val tvName: TextView = v.findViewById(android.R.id.text1)
        val tvSub: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1F2E.toInt())
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(16, 12, 16, 12)
        }

        val cb = CheckBox(parent.context).apply {
            id = android.R.id.checkbox
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
        }

        val textLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(12, 0, 0, 0)
        }

        val tv1 = TextView(parent.context).apply {
            id = android.R.id.text1
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }

        val tv2 = TextView(parent.context).apply {
            id = android.R.id.text2
            setTextColor(0xFF888888.toInt())
            textSize = 11f
        }

        textLayout.addView(tv1)
        textLayout.addView(tv2)
        root.addView(cb)
        root.addView(textLayout)

        return VH(root)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.tvName.text = if (p.profileName.isNotEmpty()) p.profileName else "HTTP Proxy ${position + 1}"
        holder.tvSub.text = "${p.proxyHost}:${p.proxyPort} → SSH ${p.sshHost}:${p.sshPort}"

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = p.isSelected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            profiles[holder.adapterPosition].isSelected = checked
            onSelectionChanged(p.id, checked)
        }

        // Appui long → menu contextuel (style V2ray DNS)
        holder.itemView.setOnLongClickListener {
            val popup = PopupMenu(holder.itemView.context, holder.itemView)
            popup.menu.add(0, 0, 0, "✏️ Modifier")
            popup.menu.add(0, 1, 1, "📋 Cloner")
            popup.menu.add(0, 2, 2, "🗑️ Supprimer")
            popup.setOnMenuItemClickListener { item ->
                val pos = holder.adapterPosition
                when (item.itemId) {
                    0 -> onEdit(profiles[pos])
                    1 -> onClone(profiles[pos])
                    2 -> onDelete(profiles[pos])
                }
                true
            }
            popup.show()
            true
        }
    }

    override fun getItemCount() = profiles.size

    fun setProfiles(newList: List<HttpProxyProfile>) {
        profiles = newList.toMutableList()
        notifyDataSetChanged()
    }

    fun getSelected() = profiles.filter { it.isSelected }
}
