package ru.kolco24.kolco24.ui.photo;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import ru.kolco24.kolco24.data.entities.Photo;


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
        holder.bind(current);
    }

    static class PhotoPointDiff extends DiffUtil.ItemCallback<Photo> {
        @Override
        public boolean areItemsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Photo oldItem, @NonNull Photo newItem) {
            return (oldItem.getPointNumber() == newItem.getPointNumber() &&
                    oldItem.getPhotoThumbUrl().equals(newItem.getPhotoThumbUrl()) &&
                    oldItem.isSync() == newItem.isSync() &&
                    oldItem.isSyncLocal() == newItem.isSyncLocal());
        }
    }
}
