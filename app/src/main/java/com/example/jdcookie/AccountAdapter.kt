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

class AccountAdapter(
    private val items: MutableList<Account>,
    private val listener: Listener
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    interface Listener {
        fun onCopy(account: Account)
        fun onRelogin(account: Account)
        fun onRename(account: Account)
        fun onDelete(account: Account)
        fun onCheck(account: Account)
        fun onPushQl(account: Account)
        fun onShowQr(account: Account)
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private var beanById: Map<String, JdBeanApi.AccountBeanStats> = emptyMap()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvPin: TextView = v.findViewById(R.id.tvPin)
        val tvUpdated: TextView = v.findViewById(R.id.tvUpdated)
        val tvPush: TextView = v.findViewById(R.id.tvPush)
        val tvBeans: TextView = v.findViewById(R.id.tvBeans)
        val tvBeansExpire: TextView = v.findViewById(R.id.tvBeansExpire)
        val chipStatus: Chip = v.findViewById(R.id.chipStatus)
        val btnCopy: MaterialButton = v.findViewById(R.id.btnCopy)
        val btnRelogin: MaterialButton = v.findViewById(R.id.btnRelogin)
        val btnMore: MaterialButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        val ctx = holder.itemView.context
        holder.tvName.text = a.displayName
        holder.tvPin.text = "pt_pin: ${a.decodedPin}"
        val t = if (a.updatedAt > 0) fmt.format(Date(a.updatedAt)) else "-"
        val c = if (a.lastCheckedAt > 0) fmt.format(Date(a.lastCheckedAt)) else "-"
        holder.tvUpdated.text = "更新 $t · 检测 $c"
        holder.tvPush.text = when (a.pushStatus) {
            PushStatus.SYNCED -> {
                val pt = if (a.lastPushedAt > 0) fmt.format(Date(a.lastPushedAt)) else ""
                ctx.getString(R.string.push_synced, pt)
            }
            PushStatus.FAILED -> ctx.getString(R.string.push_failed, a.lastPushMessage)
            PushStatus.PUSHING -> ctx.getString(R.string.push_pushing)
            PushStatus.NONE -> ctx.getString(R.string.push_none)
        }
        holder.tvPush.setTextColor(
            ctx.getColor(
                when (a.pushStatus) {
                    PushStatus.SYNCED -> R.color.status_ok
                    PushStatus.FAILED -> R.color.md_theme_error
                    else -> R.color.status_warn
                }
            )
        )
        bindBeans(holder, a)
        bindStatus(holder.chipStatus, a)
        holder.btnCopy.setOnClickListener { listener.onCopy(a) }
        holder.btnRelogin.setOnClickListener { listener.onRelogin(a) }
        holder.btnMore.setOnClickListener { v ->
            val popup = android.widget.PopupMenu(v.context, v)
            popup.menu.add(0, 1, 0, R.string.rename)
            popup.menu.add(0, 2, 1, R.string.check_one)
            popup.menu.add(0, 3, 2, R.string.push_qinglong)
            popup.menu.add(0, 4, 3, R.string.show_qr)
            popup.menu.add(0, 5, 4, R.string.delete)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> listener.onRename(a)
                    2 -> listener.onCheck(a)
                    3 -> listener.onPushQl(a)
                    4 -> listener.onShowQr(a)
                    5 -> listener.onDelete(a)
                }
                true
            }
            popup.show()
        }
    }

    private fun bindBeans(holder: VH, a: Account) {
        val ctx = holder.itemView.context
        val stats = beanById[a.id]
        if (stats == null) {
            holder.tvBeans.text = ctx.getString(R.string.bean_card_placeholder)
            holder.tvBeansExpire.text = ctx.getString(R.string.bean_card_expire_placeholder)
            return
        }
        if (!stats.ok) {
            holder.tvBeans.text = ctx.getString(R.string.bean_card_fail)
            holder.tvBeansExpire.text = stats.message.ifBlank {
                ctx.getString(R.string.bean_card_expire_na)
            }
            return
        }
        val balance = stats.totalBeans?.toString() ?: ctx.getString(R.string.bean_na)
        val today = stats.todayIncome?.let { "+$it" } ?: ctx.getString(R.string.bean_na)
        holder.tvBeans.text = if (stats.totalBeans != null && stats.todayIncome != null) {
            ctx.getString(R.string.bean_card_line, stats.totalBeans, stats.todayIncome)
        } else {
            ctx.getString(R.string.bean_card_line_partial, balance, today)
        }
        holder.tvBeansExpire.text = if (!stats.expireAvailable) {
            ctx.getString(R.string.bean_card_expire_na)
        } else {
            ctx.getString(
                R.string.bean_card_expire,
                stats.expireTomorrow ?: 0,
                stats.expireIn7Days ?: 0
            )
        }
    }

    private fun bindStatus(chip: Chip, a: Account) {
        val ctx = chip.context
        when (a.status) {
            AccountStatus.VALID -> {
                chip.text = ctx.getString(R.string.status_valid)
                chip.setChipBackgroundColorResource(R.color.status_ok_bg)
                chip.setTextColor(ctx.getColor(R.color.status_ok))
            }
            AccountStatus.EXPIRED -> {
                chip.text = ctx.getString(R.string.status_expired)
                chip.setChipBackgroundColorResource(R.color.status_err_bg)
                chip.setTextColor(ctx.getColor(R.color.md_theme_error))
            }
            AccountStatus.CHECKING -> {
                chip.text = ctx.getString(R.string.status_checking)
                chip.setChipBackgroundColorResource(R.color.status_warn_bg)
                chip.setTextColor(ctx.getColor(R.color.status_warn))
            }
            AccountStatus.ERROR -> {
                chip.text = a.lastMessage.ifBlank { ctx.getString(R.string.status_error) }
                chip.setChipBackgroundColorResource(R.color.status_warn_bg)
                chip.setTextColor(ctx.getColor(R.color.status_warn))
            }
            AccountStatus.UNKNOWN -> {
                chip.text = ctx.getString(R.string.status_unknown)
                chip.setChipBackgroundColorResource(R.color.status_warn_bg)
                chip.setTextColor(ctx.getColor(R.color.status_warn))
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(newItems: List<Account>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setBeanStats(stats: Map<String, JdBeanApi.AccountBeanStats>) {
        beanById = stats
        notifyDataSetChanged()
    }
}
