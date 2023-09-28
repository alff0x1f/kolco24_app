package ru.kolco24.kolco24.ui.legends;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import ru.kolco24.kolco24.data.entities.Point;

public class PointListAdapter extends ListAdapter<Point.PointExt, PointViewHolder> {

    /*__init__*/
    public PointListAdapter(@NonNull DiffUtil.ItemCallback<Point.PointExt> diffCallback) {
        super(diffCallback);
    }

    @NonNull
    @Override
    public PointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return PointViewHolder.create(parent);
    }

    public void onBindViewHolder(PointViewHolder holder, int position) {
        Point.PointExt current = getItem(position);
        holder.bind(current);
    }

    static class PointDiff extends DiffUtil.ItemCallback<Point.PointExt> {

        @Override
        public boolean areItemsTheSame(@NonNull Point.PointExt oldItem, @NonNull Point.PointExt newItem) {
            return oldItem == newItem;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Point.PointExt oldItem, @NonNull Point.PointExt newItem) {
            return oldItem.number == newItem.number &&
                    oldItem.description.equals(newItem.description) &&
                    oldItem.cost == newItem.cost;
        }
    }
}
