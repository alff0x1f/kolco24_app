package ru.kolco24.kolco24.ui.photo;

import android.content.Intent;
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

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        photoKP = itemView.findViewById(R.id.photoKP);
        syncLabel = itemView.findViewById(R.id.syncLabel);
        pointIcon = itemView.findViewById(R.id.pointIcon);
        logo = itemView.findViewById(R.id.logo);
    }

    public void bind(Photo photo) {
        if (photo.photo_url.equals("add_photo") || photo.photo_url.equals("add_from_gallery")) {
            logo.setVisibility(View.VISIBLE);
            syncLabel.setVisibility(View.GONE);
            pointIcon.setVisibility(View.GONE);
            photoKP.setBackgroundColor(itemView.getContext().getResources().getColor(R.color.colorGray));
            if (photo.photo_url.equals("add_photo")) {
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

        if (photo.photo_url.equals("nfc_check")){
            logo.setVisibility(View.VISIBLE);
            syncLabel.setVisibility(View.GONE);
            photoKP.setBackgroundColor(itemView.getContext().getResources().getColor(R.color.colorGray));
            logo.setImageResource(R.drawable.mobile_pay);
            textView.setText(String.format("%02d", photo.point_number));
            return;
        }

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
