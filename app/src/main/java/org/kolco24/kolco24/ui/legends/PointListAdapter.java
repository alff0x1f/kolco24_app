package org.kolco24.kolco24.ui.legends;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import org.kolco24.kolco24.data.Point;

public class PointListAdapter extends ListAdapter<Point, PointViewHolder> {

    public PointListAdapter(@NonNull DiffUtil.ItemCallback<Point> diffCallback) {
        super(diffCallback);
    }

    @Override
    public PointViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return PointViewHolder.create(parent);
    }

    public void onBindViewHolder(PointViewHolder holder, int position) {
        Point current = getItem(position);
        holder.bind(current.getPoint());
    }

    static class PointDiff extends DiffUtil.ItemCallback<Point> {

        @Override
        public boolean areItemsTheSame(@NonNull Point oldItem, @NonNull Point newItem) {
            return oldItem == newItem;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Point oldItem, @NonNull Point newItem) {
            return oldItem.getPoint().equals(newItem.getPoint());
        }
    }
}
