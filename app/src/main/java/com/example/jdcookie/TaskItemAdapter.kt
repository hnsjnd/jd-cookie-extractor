package com.example.jdcookie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class TaskRow(
    val id: String,
    var title: String,
    val subtitle: String,
    var progress: Int = 0, // 0-100
    var result: String = "",
    var done: Boolean = false
)

class TaskItemAdapter(private val items: MutableList<TaskRow>) :
    RecyclerView.Adapter<TaskItemAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvTaskTitle)
        val tvSub: TextView = v.findViewById(R.id.tvTaskSub)
        val tvResult: TextView = v.findViewById(R.id.tvTaskResult)
        val bar: ProgressBar = v.findViewById(R.id.taskProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.tvTitle.text = t.title
        holder.tvSub.text = t.subtitle
        holder.tvResult.text = t.result
        holder.bar.isIndeterminate = !t.done && t.progress == 0
        if (t.done || t.progress > 0) {
            holder.bar.isIndeterminate = false
            holder.bar.progress = t.progress.coerceIn(0, 100)
        }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(newItems: List<TaskRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun update(id: String, transform: (TaskRow) -> Unit) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            transform(items[idx])
            notifyItemChanged(idx)
        }
    }
}
