package ru.kolco24.kolco24.ui.teams;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import ru.kolco24.kolco24.data.entities.Team;

public class TeamListAdapter extends ListAdapter<Team, TeamViewHolder> {

    /*__init__*/
    public TeamListAdapter(@NonNull DiffUtil.ItemCallback<Team> diffCallback) {
        super(diffCallback);
    }

    @NonNull
    @Override
    public TeamViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return TeamViewHolder.create(parent);
    }

    public void onBindViewHolder(TeamViewHolder holder, int position) {
        Team current = getItem(position);
        holder.bind(current);
    }

    static class TeamDiff extends DiffUtil.ItemCallback<Team> {

        @Override
        public boolean areItemsTheSame(@NonNull Team oldItem, @NonNull Team newItem) {
            return oldItem == newItem;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Team oldItem, @NonNull Team newItem) {
            return oldItem.getStartNumber().equals(newItem.getStartNumber());
        }
    }
}
