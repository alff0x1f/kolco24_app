package ru.kolco24.kolco24.ui.legends;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.NewPhotoActivity;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.Point;

public class PointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView textDescription;
    private final TextView textCost;
    private final TextView textPointNumber;

    /*__init__*/
    private PointViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        textDescription = itemView.findViewById(R.id.textDescription);
        textCost = itemView.findViewById(R.id.textCost);
        textPointNumber = itemView.findViewById(R.id.pointNumber);
    }

    public void bind(Point.PointExt point) {
        textView.setText(String.format("%02d", point.number));
        textPointNumber.setText("14:05");

        textDescription.setText(point.description);
        textCost.setText(String.format("+%d", point.cost));
        Drawable drawable = ContextCompat.getDrawable(itemView.getContext(), R.drawable.cost_background);
        // set color based on cost
        int color = Color.parseColor("#000000");
        if (point.cost == 1) {
            color = itemView.getResources().getColor(R.color.level1);
        } else if (point.cost == 2) {
            color = itemView.getResources().getColor(R.color.level2);
        } else if (point.cost == 3) {
            color = itemView.getResources().getColor(R.color.level3);
        } else if (point.cost == 4) {
            color = itemView.getResources().getColor(R.color.level4);
        } else if (point.cost == 5) {
            color = itemView.getResources().getColor(R.color.level5);
        } else if (point.cost == 6) {
            color = itemView.getResources().getColor(R.color.level6);
        } else if (point.cost == 7) {
            color = itemView.getResources().getColor(R.color.level7);
        } else if (point.cost == 8) {
            color = itemView.getResources().getColor(R.color.level8);
        } else if (point.cost == 9) {
            color = itemView.getResources().getColor(R.color.level9);
        } else {
            color = itemView.getResources().getColor(R.color.level10);
        }
        if (point.photo_thumb_url != null) {
            color = itemView.getResources().getColor(R.color.pointIconPlaceholder);
        }
        drawable.mutate().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        textCost.setBackground(drawable);

        if (point.photo_thumb_url != null) {
//            ImageView photo_thumb = itemView.findViewById(R.id.photo_thumb);
//            photo_thumb.setImageURI(Uri.parse(point.photo_thumb_url));

            textPointNumber.setTextColor(Color.parseColor("#999999"));

//            itemView.findViewById(R.id.full_item).setBackgroundColor(Color.parseColor("#f0f0f0"));
        }

        // on click listener
        itemView.setOnClickListener(v -> {
            itemView.setBackgroundColor(itemView.getResources().getColor(R.color.divider));
            Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
            intent.putExtra("point_number", point.number);
            itemView.getContext().startActivity(intent);
        });
    }

    /**
     * Creates a new ViewHolder.
     *
     * @param parent The ViewGroup into which the new View will be added
     *               after it is bound to an adapter position.
     * @return A new ViewHolder that holds a View of the given view type.
     */
    static PointViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.recyclerview_item, parent, false);
        return new PointViewHolder(view);
    }
}
