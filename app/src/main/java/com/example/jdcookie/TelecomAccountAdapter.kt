package com.example.jdcookie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelecomAccountAdapter(
    private val items: MutableList<TelecomAccount>,
    private val listener: Listener
) : RecyclerView.Adapter<TelecomAccountAdapter.VH>() {

    interface Listener {
        fun onCopy(account: TelecomAccount)
        fun onPushQl(account: TelecomAccount)
        fun onRename(account: TelecomAccount)
        fun onDelete(account: TelecomAccount)
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvToken: TextView = v.findViewById(R.id.tvToken)
        val tvUpdated: TextView = v.findViewById(R.id.tvUpdated)
        val tvPushMsg: TextView = v.findViewById(R.id.tvPushMsg)
        val chipPush: Chip = v.findViewById(R.id.chipPush)
        val btnCopy: MaterialButton = v.findViewById(R.id.btnCopy)
        val btnPush: MaterialButton = v.findViewById(R.id.btnPush)
        val btnMore: MaterialButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_meituan_account, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        val ctx = holder.itemView.context
        holder.tvName.text = a.displayName
        // Masked — never show full service password in the list
        holder.tvToken.text = a.credentialPreview
        val t = if (a.updatedAt > 0) fmt.format(Date(a.updatedAt)) else "-"
        holder.tvUpdated.text = "更新 $t"
        holder.tvPushMsg.text = when (a.pushStatus) {
            PushStatus.SYNCED -> {
                val pt = if (a.lastPushedAt > 0) fmt.format(Date(a.lastPushedAt)) else ""
                ctx.getString(R.string.push_synced, pt)
            }
            PushStatus.FAILED -> ctx.getString(R.string.push_failed, a.lastPushMessage)
            PushStatus.PUSHING -> ctx.getString(R.string.push_pushing)
            PushStatus.NONE -> ctx.getString(R.string.push_none)
        }
        holder.tvPushMsg.setTextColor(
            ctx.getColor(
                when (a.pushStatus) {
                    PushStatus.SYNCED -> R.color.status_ok
                    PushStatus.FAILED -> R.color.md_theme_error
                    else -> R.color.status_warn
                }
            )
        )
        when (a.pushStatus) {
            PushStatus.SYNCED -> {
                holder.chipPush.text = ctx.getString(R.string.status_synced_short)
                holder.chipPush.setChipBackgroundColorResource(R.color.status_ok_bg)
                holder.chipPush.setTextColor(ctx.getColor(R.color.status_ok))
            }
            PushStatus.FAILED -> {
                holder.chipPush.text = ctx.getString(R.string.status_failed_short)
                holder.chipPush.setChipBackgroundColorResource(R.color.status_err_bg)
                holder.chipPush.setTextColor(ctx.getColor(R.color.md_theme_error))
            }
            PushStatus.PUSHING -> {
                holder.chipPush.text = ctx.getString(R.string.status_pushing_short)
                holder.chipPush.setChipBackgroundColorResource(R.color.status_warn_bg)
                holder.chipPush.setTextColor(ctx.getColor(R.color.status_warn))
            }
            PushStatus.NONE -> {
                holder.chipPush.text = ctx.getString(R.string.status_unsynced_short)
                holder.chipPush.setChipBackgroundColorResource(R.color.status_warn_bg)
                holder.chipPush.setTextColor(ctx.getColor(R.color.status_warn))
            }
        }
        holder.btnCopy.setOnClickListener { listener.onCopy(a) }
        holder.btnPush.setOnClickListener { listener.onPushQl(a) }
        holder.btnMore.setOnClickListener { v ->
            val popup = android.widget.PopupMenu(v.context, v)
            popup.menu.add(0, 1, 0, R.string.rename)
            popup.menu.add(0, 2, 1, R.string.push_qinglong)
            popup.menu.add(0, 3, 2, R.string.delete)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> listener.onRename(a)
                    2 -> listener.onPushQl(a)
                    3 -> listener.onDelete(a)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(newItems: List<TelecomAccount>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
