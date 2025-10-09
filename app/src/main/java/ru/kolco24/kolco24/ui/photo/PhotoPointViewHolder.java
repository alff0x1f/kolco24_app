package ru.kolco24.kolco24.ui.photo;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Photo;

public class PhotoPointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final ImageView photoKP;
    private final ImageView syncLabel;
    private final CardView pointIcon;
    private final ImageView logo;
    private final ImageView nfcBadge;

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        photoKP = itemView.findViewById(R.id.photoKP);
        syncLabel = itemView.findViewById(R.id.syncLabel);
        pointIcon = itemView.findViewById(R.id.pointIcon);
        logo = itemView.findViewById(R.id.logo);
        nfcBadge = itemView.findViewById(R.id.nfcBadge);
    }

    public void bind(Photo photo) {
        logo.setVisibility(View.GONE);
        nfcBadge.setVisibility(View.GONE);
        pointIcon.setVisibility(View.VISIBLE);
        photoKP.setImageDrawable(null);
        photoKP.setBackgroundColor(Color.TRANSPARENT);

        if (photo.getPhotoUrl().equals("add_photo") || photo.getPhotoUrl().equals("add_from_gallery")) {
            logo.setVisibility(View.VISIBLE);
            syncLabel.setVisibility(View.GONE);
            pointIcon.setVisibility(View.GONE);
            nfcBadge.setVisibility(View.GONE);
            photoKP.setBackgroundColor(itemView.getContext().getResources().getColor(R.color.colorGray));
            if (photo.getPhotoUrl().equals("add_photo")) {
                logo.setImageResource(R.drawable.ic_baseline_add_a_photo_24);
                itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
                    itemView.getContext().startActivity(intent);
                });
            } else {
                logo.setImageResource(R.drawable.ic_baseline_add_photo_alternate_24);
                itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
                    intent.putExtra("fromGallery", true);
                    itemView.getContext().startActivity(intent);
                });
            }
            return;
        }

        textView.setText(String.format("%02d", photo.getPointNumber()));
        if (!photo.getPhotoThumbUrl().isEmpty()) {
            photoKP.setImageURI(Uri.parse(photo.getPhotoThumbUrl()));
        }

        if (!photo.getPointNfc().equals("")) {
            if (photo.getPhotoThumbUrl().isEmpty()) {
                logo.setVisibility(View.VISIBLE);
                logo.setImageResource(R.drawable.mobile_pay);
                nfcBadge.setVisibility(View.GONE);
                photoKP.setBackgroundColor(itemView.getContext().getResources().getColor(R.color.colorGray));
                photoKP.setImageDrawable(null);
            } else {
                nfcBadge.setVisibility(View.VISIBLE);
            }

            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
                intent.putExtra("id", photo.getId());
                intent.putExtra("point_number", photo.getPointNumber());
                intent.putExtra("fromGallery", true);
                itemView.getContext().startActivity(intent);
            });
        } else {
            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
                intent.putExtra("id", photo.getId());
                intent.putExtra("point_number", photo.getPointNumber());
                intent.putExtra("photo_uri", photo.getPhotoUrl());
                intent.putExtra("photo_thumb_uri", photo.getPhotoThumbUrl());
                itemView.getContext().startActivity(intent);
            });
        }


        showSyncLabel(photo);
    }

    private void showSyncLabel(Photo photo) {
        if (photo.isSync() && photo.isSyncLocal()) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorGreen));
        } else if (photo.isSync()) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorBlue));
        } else if (photo.isSyncLocal()) {
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
