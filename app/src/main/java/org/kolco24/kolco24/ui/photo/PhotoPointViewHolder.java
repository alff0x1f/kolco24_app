package org.kolco24.kolco24.ui.photo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.R;
import org.kolco24.kolco24.data.PhotoInfo;

public class PhotoPointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;

    public PhotoPointViewHolder(@NonNull View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
    }

    public void bind(PhotoInfo point) {
        textView.setText(point.getPoint_number());
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
