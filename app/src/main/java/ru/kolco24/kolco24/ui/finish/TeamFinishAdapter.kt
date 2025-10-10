package ru.kolco24.kolco24.ui.finish

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.entities.TeamFinish
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TeamFinishAdapter : RecyclerView.Adapter<TeamFinishAdapter.ViewHolder>() {
    private val items = mutableListOf<TeamFinish>()
    private val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun submit(data: List<TeamFinish>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_team_finish, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], formatter)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val hex: TextView = itemView.findViewById(R.id.finishTagHex)
        private val info: TextView = itemView.findViewById(R.id.finishTagInfo)
        private val timestamp: TextView = itemView.findViewById(R.id.finishTagTimestamp)

        fun bind(item: TeamFinish, formatter: SimpleDateFormat) {
            hex.text = item.tagUid
            info.text = itemView.context.getString(
                R.string.team_finish_entry_info,
                if (item.isSyncLocal) itemView.context.getString(R.string.team_finish_status_synced)
                else itemView.context.getString(R.string.team_finish_status_pending),
                if (item.isSyncRemote) itemView.context.getString(R.string.team_finish_status_synced)
                else itemView.context.getString(R.string.team_finish_status_pending)
            )
            timestamp.text = formatter.format(Date(item.recordedAt))
        }
    }
}
