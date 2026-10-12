package com.example.jdcookie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val items: MutableList<CookieRecord>,
    private val onCopy: (CookieRecord) -> Unit,
    private val onDelete: (CookieRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvPin: TextView = view.findViewById(R.id.tvPin)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvCookiePreview: TextView = view.findViewById(R.id.tvCookiePreview)
        val btnCopy: MaterialButton = view.findViewById(R.id.btnCopyItem)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvPin.text = item.displayPin
        holder.tvTime.text = holder.itemView.context.getString(
            R.string.saved_at,
            fmt.format(Date(item.savedAt))
        )
        holder.tvCookiePreview.text = item.combined
        holder.btnCopy.setOnClickListener { onCopy(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(newItems: List<CookieRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
