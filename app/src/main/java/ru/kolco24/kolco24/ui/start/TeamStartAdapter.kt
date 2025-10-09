package ru.kolco24.kolco24.ui.start

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.entities.TeamStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TeamStartAdapter : RecyclerView.Adapter<TeamStartAdapter.ViewHolder>() {
    private val items = mutableListOf<TeamStart>()
    private val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun submit(data: List<TeamStart>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_team_start, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], formatter)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.startTeamTitle)
        private val details: TextView = itemView.findViewById(R.id.startTeamDetails)
        private val timestamp: TextView = itemView.findViewById(R.id.startTeamTimestamp)

        fun bind(item: TeamStart, formatter: SimpleDateFormat) {
            title.text = itemView.context.getString(
                R.string.team_start_entry_title,
                item.startNumber,
                item.teamName
            )
            details.text = itemView.context.getString(
                R.string.team_start_entry_details,
                item.participantCount,
                item.scannedCount,
                if (item.isSyncLocal) itemView.context.getString(R.string.team_start_status_synced)
                else itemView.context.getString(R.string.team_start_status_pending),
                if (item.isSync) itemView.context.getString(R.string.team_start_status_synced)
                else itemView.context.getString(R.string.team_start_status_pending)
            )
            timestamp.text = formatter.format(Date(item.startTimestamp))
        }
    }
}
