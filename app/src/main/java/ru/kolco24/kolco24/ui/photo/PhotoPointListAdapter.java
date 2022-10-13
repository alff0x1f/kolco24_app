package ru.kolco24.kolco24.ui.photo;

import android.net.Uri;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import ru.kolco24.kolco24.data.Photo;


public class PhotoPointListAdapter extends ListAdapter<Photo, PhotoPointViewHolder> {

    public PhotoPointListAdapter(@NonNull DiffUtil.ItemCallback<Photo> diffCallback) {
        super(diffCallback);
    }

    @NonNull
    @Override
    public PhotoPointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return PhotoPointViewHolder.create(parent);
    }

    public void onBindViewHolder(PhotoPointViewHolder holder, int position) {
        Photo current = getItem(position);
        holder.bind(
                current.id,
                current.point_number,
                Uri.parse(current.photo_url),
                Uri.parse(current.photo_thumb_url),
                current.sync_internet,
                current.sync_local
        );
    }

    static class PhotoPointDiff extends DiffUtil.ItemCallback<Photo> {
        @Override
        public boolean areItemsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return (oldItem.point_number == newItem.point_number &&
                    oldItem.photo_thumb_url.equals(newItem.photo_thumb_url) &&
                    oldItem.sync_internet == newItem.sync_internet &&
                    oldItem.sync_local == newItem.sync_local);
        }
    }
}
