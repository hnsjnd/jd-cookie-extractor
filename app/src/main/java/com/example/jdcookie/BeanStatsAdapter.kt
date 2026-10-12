package com.example.jdcookie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BeanStatsAdapter(
    private val items: MutableList<JdBeanApi.AccountBeanStats>
) : RecyclerView.Adapter<BeanStatsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvBeanName)
        val tvTotal: TextView = v.findViewById(R.id.tvBeanTotal)
        val tvToday: TextView = v.findViewById(R.id.tvBeanToday)
        val tvExpire: TextView = v.findViewById(R.id.tvBeanExpire)
        val tvMsg: TextView = v.findViewById(R.id.tvBeanMsg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bean_account, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        holder.tvName.text = r.displayName
        if (!r.ok) {
            holder.tvTotal.text = ctx.getString(R.string.bean_na)
            holder.tvToday.text = "-"
            holder.tvExpire.text = "-"
            holder.tvMsg.text = r.message
            holder.tvMsg.visibility = View.VISIBLE
            return
        }
        holder.tvTotal.text = r.totalBeans?.let { ctx.getString(R.string.bean_total_fmt, it) }
            ?: ctx.getString(R.string.bean_na)
        val income = r.todayIncome
        val expense = r.todayExpense
        holder.tvToday.text = when {
            income == null -> ctx.getString(R.string.bean_today_na)
            else -> {
                val exp = expense ?: 0
                ctx.getString(R.string.bean_today_fmt, income, kotlin.math.abs(exp))
            }
        }
        holder.tvExpire.text = if (!r.expireAvailable) {
            ctx.getString(R.string.bean_expire_unavailable)
        } else {
            val tm = r.expireTomorrow ?: 0
            val w7 = r.expireIn7Days ?: 0
            val extra = r.expireBuckets.take(4).joinToString("；") {
                "${it.dayLabel} ${it.amount}"
            }
            if (extra.isBlank()) {
                ctx.getString(R.string.bean_expire_fmt, tm, w7)
            } else {
                ctx.getString(R.string.bean_expire_fmt, tm, w7) + "\n" + extra
            }
        }
        if (r.message.isNotBlank() && r.message != "OK") {
            holder.tvMsg.text = r.message
            holder.tvMsg.visibility = View.VISIBLE
        } else {
            holder.tvMsg.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(rows: List<JdBeanApi.AccountBeanStats>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }
}
