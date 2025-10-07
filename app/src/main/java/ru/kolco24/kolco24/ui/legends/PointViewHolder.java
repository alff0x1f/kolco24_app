package ru.kolco24.kolco24.ui.legends;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Checkpoint;

public class PointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView textDescription;
    private final TextView textCost;
    private final TextView pointTimeTextView;
    private final LinearLayout tagIconContainer;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm");


    /*__init__*/
    private PointViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        textDescription = itemView.findViewById(R.id.textDescription);
        textCost = itemView.findViewById(R.id.textCost);
        pointTimeTextView = itemView.findViewById(R.id.pointTimeTextView);
        tagIconContainer = itemView.findViewById(R.id.tagIconContainer);
    }

    public void bind(Checkpoint.PointExt point) {
        textView.setText(String.format("%02d", point.getNumber()));

        if (point.getTime() != null) {
            Date date = new Date(point.getTime());
            String formattedDate = dateFormat.format(date);

            pointTimeTextView.setText(formattedDate);
            // make strike through
            textDescription.setPaintFlags(textDescription.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            pointTimeTextView.setText("");
        }
        if (point.getDescription().isEmpty()) {
            textDescription.setText("Описание пока скрыто");
            textDescription.setTypeface(null, android.graphics.Typeface.ITALIC);
        } else {
            textDescription.setText(point.getDescription());
            textDescription.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        textCost.setText(String.format("%d-%02d", point.getCost(), point.getNumber()));
        Drawable drawable = ContextCompat.getDrawable(itemView.getContext(), R.drawable.cost_background);
        // set color based on cost
        int color;
        if (point.getCost() == 0) {
            color = itemView.getResources().getColor(R.color.level0);
        } else if (point.getCost() == 1) {
            color = itemView.getResources().getColor(R.color.level1);
        } else if (point.getCost() == 2) {
            color = itemView.getResources().getColor(R.color.level2);
        } else if (point.getCost() == 3) {
            color = itemView.getResources().getColor(R.color.level3);
        } else if (point.getCost() == 4) {
            color = itemView.getResources().getColor(R.color.level4);
        } else if (point.getCost() == 5) {
            color = itemView.getResources().getColor(R.color.level5);
        } else if (point.getCost() == 6) {
            color = itemView.getResources().getColor(R.color.level6);
        } else if (point.getCost() == 7) {
            color = itemView.getResources().getColor(R.color.level7);
        } else if (point.getCost() == 8) {
            color = itemView.getResources().getColor(R.color.level8);
        } else if (point.getCost() == 9) {
            color = itemView.getResources().getColor(R.color.level9);
        } else {
            color = itemView.getResources().getColor(R.color.level10);
        }
        // change color background
        drawable.mutate().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        textCost.setBackground(drawable);

        if (point.getTagCount() > 0) {
            tagIconContainer.setVisibility(View.VISIBLE);
            tagIconContainer.removeAllViews();

            int iconSize = dpToPx(12);
            int iconMargin = dpToPx(4);
            int tintColor = ContextCompat.getColor(itemView.getContext(), R.color.pointIconPlaceholderText);

            for (int i = 0; i < point.getTagCount(); i++) {
                ImageView imageView = new ImageView(itemView.getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSize, iconSize);
                if (i != 0) {
                    params.setMarginStart(iconMargin);
                }
                imageView.setLayoutParams(params);
                imageView.setImageResource(R.drawable.ic_baseline_nfc_24);
                imageView.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
                tagIconContainer.addView(imageView);
            }
        } else {
            tagIconContainer.setVisibility(View.GONE);
        }


        // on click listener
        //        itemView.setOnClickListener(v -> {
        //            Intent intent = new Intent(itemView.getContext(), NewPhotoActivity.class);
        //            intent.putExtra("point_number", point.getNumber());
        //            itemView.getContext().startActivity(intent);
        //        });
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
    private int dpToPx(int dp) {
        float density = itemView.getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }
}
