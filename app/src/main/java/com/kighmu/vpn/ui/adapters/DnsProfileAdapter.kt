package com.kighmu.vpn.ui.adapters

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.models.SlowDnsConfig

class DnsProfileAdapter(
    private val profiles: MutableList<SlowDnsConfig>,
    private val onEdit: (Int, SlowDnsConfig) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onClone: (Int) -> Unit
) : RecyclerView.Adapter<DnsProfileAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(android.R.id.text1)
        val tvSub: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        v.setBackgroundColor(0xFF1A1F2E.toInt())
        v.setPadding(16, 12, 16, 12)
        return VH(v)
    }

    override fun getItemCount() = profiles.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.tvName.text = "Profil ${position + 1} — ${p.nameserver.ifEmpty { "no nameserver" }}"
        holder.tvName.setTextColor(0xFFFFFFFF.toInt())
        holder.tvSub.text = "DNS: ${p.dnsServer}  Key: ${if (p.publicKey.length > 12) p.publicKey.take(12) + "..." else p.publicKey}"
        holder.tvSub.setTextColor(0xFF888888.toInt())

        holder.itemView.setOnClickListener {
            val popup = PopupMenu(holder.itemView.context, holder.itemView)
            popup.menu.add(0, 0, 0, "Modifier")
            popup.menu.add(0, 1, 1, "Cloner")
            popup.menu.add(0, 2, 2, "Supprimer")
            popup.setOnMenuItemClickListener { item ->
                val pos = holder.adapterPosition
                when (item.itemId) {
                    0 -> onEdit(pos, profiles[pos])
                    1 -> onClone(pos)
                    2 -> onDelete(pos)
                }
                true
            }
            popup.show()
        }
    }

    fun addProfile(p: SlowDnsConfig) { profiles.add(p); notifyItemInserted(profiles.size - 1) }
    fun updateProfile(i: Int, p: SlowDnsConfig) { profiles[i] = p; notifyItemChanged(i) }
    fun removeProfile(i: Int) { profiles.removeAt(i); notifyItemRemoved(i) }
    fun cloneProfile(i: Int) { profiles.add(i + 1, profiles[i].copy()); notifyItemInserted(i + 1) }
    fun getProfiles(): MutableList<SlowDnsConfig> = profiles
}
