package ru.kolco24.kolco24.ui.photo;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Photo;

public class PhotoPointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final ImageView photoKP;
    private final ImageView syncLabel;

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        photoKP = itemView.findViewById(R.id.photoKP);
        syncLabel = itemView.findViewById(R.id.syncLabel);
    }

    public void bind(Photo photo) {
        textView.setText(String.format("%02d", photo.point_number));
        photoKP.setImageURI(Uri.parse(photo.photo_thumb_url));

        itemView.setOnClickListener(v -> {
            Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
            intent.putExtra("id", photo.id);
            intent.putExtra("point_number", photo.point_number);
            intent.putExtra("photo_uri", photo.photo_url);
            intent.putExtra("photo_thumb_uri", photo.photo_thumb_url);
            itemView.getContext().startActivity(intent);
        });

        if (photo.sync_internet && photo.sync_local) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorGreen));
        } else if (photo.sync_internet) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorBlue));
        } else if (photo.sync_local) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorYellow));
        } else {
            syncLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Creates a new PhotoPointViewHolder.
     *
     * @param parent The PhotoPointViewHolder into which the new View will be added
     *               after it is bound to an adapter position.
     * @return A new PhotoPointViewHolder that holds a View of the given view type.
     */
    static PhotoPointViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.photo_item, parent, false);
        return new PhotoPointViewHolder(view);
    }

}
