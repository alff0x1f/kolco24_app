package ru.kolco24.kolco24.ui.legends;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import ru.kolco24.kolco24.data.entities.Checkpoint;

public class PointListAdapter extends ListAdapter<Checkpoint.PointExt, PointViewHolder> {

    /*__init__*/
    public PointListAdapter(@NonNull DiffUtil.ItemCallback<Checkpoint.PointExt> diffCallback) {
        super(diffCallback);
    }

    @NonNull
    @Override
    public PointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return PointViewHolder.create(parent);
    }

    public void onBindViewHolder(PointViewHolder holder, int position) {
        Checkpoint.PointExt current = getItem(position);
        holder.bind(current);
    }

    static class PointDiff extends DiffUtil.ItemCallback<Checkpoint.PointExt> {

        @Override
        public boolean areItemsTheSame(@NonNull Checkpoint.PointExt oldItem, @NonNull Checkpoint.PointExt newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Checkpoint.PointExt oldItem, @NonNull Checkpoint.PointExt newItem) {
            return oldItem.getNumber() == newItem.getNumber() &&
                    oldItem.getDescription().equals(newItem.getDescription()) &&
                    oldItem.getCost() == newItem.getCost();
        }
    }
}
