package ru.kolco24.kolco24.ui.members

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.RecyclerView
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.entities.MemberTag

class MemberTagAdapter(private val memberTagsLiveData: LiveData<List<MemberTag>>) : RecyclerView.Adapter<MemberTagAdapter.MemberTagViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberTagViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.member_tag_item, parent, false)
        return MemberTagViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemberTagViewHolder, position: Int) {
        val memberTag = memberTagsLiveData.value?.get(position)
        holder.idTextView.text = "id${memberTag?.id}"
        holder.tagTextView.text = "tag: ${memberTag?.tagId}"
        holder.nameTextView.text = String.format("%04d", memberTag?.number)
    }

    override fun getItemCount(): Int {
        return memberTagsLiveData.value?.size ?: 0
    }

    class MemberTagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val idTextView: TextView = itemView.findViewById(R.id.idTextView)
        val tagTextView: TextView = itemView.findViewById(R.id.tagTextView)
        val nameTextView: TextView = itemView.findViewById(R.id.numberTextView)
    }
}
