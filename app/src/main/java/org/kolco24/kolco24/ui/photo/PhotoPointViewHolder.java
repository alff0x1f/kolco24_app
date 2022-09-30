package org.kolco24.kolco24.ui.photo;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.R;
import org.kolco24.kolco24.data.Photo;

public class PhotoPointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final ImageView photoKP;

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        photoKP = itemView.findViewById(R.id.photoKP);
    }

    public void bind(Photo photo) {
        textView.setText(photo.point_number);
        try {
            photoKP.setImageURI(Uri.parse(photo.photo_url));
        } catch (SecurityException e) {
            e.printStackTrace();
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
