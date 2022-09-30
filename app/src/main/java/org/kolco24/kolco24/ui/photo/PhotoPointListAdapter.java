package org.kolco24.kolco24.ui.photo;

import android.net.Uri;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import org.kolco24.kolco24.data.Photo;


public class PhotoPointListAdapter extends ListAdapter<Photo, PhotoPointViewHolder> {

    public PhotoPointListAdapter(@NonNull DiffUtil.ItemCallback<Photo> diffCallback) {
        super(diffCallback);
    }

    @Override
    public PhotoPointViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return PhotoPointViewHolder.create(parent);
    }

    public void onBindViewHolder(PhotoPointViewHolder holder, int position) {
        Photo current = getItem(position);
        holder.bind(current.point_number, Uri.parse(current.photo_url));
    }

    static class PhotoPointDiff extends DiffUtil.ItemCallback<Photo> {
        @Override
        public boolean areItemsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return oldItem.point_number.equals(newItem.point_number);
        }
    }
}
