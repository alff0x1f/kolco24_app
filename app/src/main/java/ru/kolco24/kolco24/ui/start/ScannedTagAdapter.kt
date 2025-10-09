package ru.kolco24.kolco24.ui.start

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.kolco24.kolco24.R

class ScannedTagAdapter : RecyclerView.Adapter<ScannedTagAdapter.ViewHolder>() {
    private val items = mutableListOf<String>()

    fun submit(tags: List<String>) {
        items.clear()
        items.addAll(tags)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scanned_tag, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView as TextView
        fun bind(tag: String) {
            text.text = tag
        }
    }
}
