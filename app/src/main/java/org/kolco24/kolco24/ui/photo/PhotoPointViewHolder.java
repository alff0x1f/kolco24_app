package org.kolco24.kolco24.ui.photo;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.NewPhotoActivity;
import org.kolco24.kolco24.R;

public class PhotoPointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final ImageView photoKP;

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        photoKP = itemView.findViewById(R.id.photoKP);
    }

    public void bind(String point_number, Uri uri) {
        textView.setText(point_number);
        photoKP.setImageURI(uri);

        itemView.setOnClickListener(v -> {
            Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
            intent.putExtra("point_number", point_number);
            intent.putExtra("uri", uri.toString());
            itemView.getContext().startActivity(intent);
        });
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
