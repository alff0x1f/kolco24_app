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

    public void bind(int id, int point_number, Uri uri, Uri uriThumb,
                     Boolean sync_internet, boolean sync_local) {
        textView.setText(Integer.toString(point_number));
        photoKP.setImageURI(uriThumb);

        itemView.setOnClickListener(v -> {
            Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
            intent.putExtra("id", id);
            intent.putExtra("point_number", point_number);
            intent.putExtra("photo_uri", uri.toString());
            intent.putExtra("photo_thumb_uri", uriThumb.toString());
            itemView.getContext().startActivity(intent);
        });

        if (sync_internet && sync_local) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorGreen));
        } else if (sync_internet) {
            syncLabel.setVisibility(View.VISIBLE);
            syncLabel.setColorFilter(itemView.getContext().getResources().getColor(R.color.colorBlue));
        } else if (sync_local) {
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
