package ru.kolco24.kolco24.ui.legends;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;

import ru.kolco24.kolco24.ui.photo.NewPhotoActivity;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Checkpoint;

public class PointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView textDescription;
    private final TextView textCost;
    private final TextView pointTimeTextView;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm");


    /*__init__*/
    private PointViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        textDescription = itemView.findViewById(R.id.textDescription);
        textCost = itemView.findViewById(R.id.textCost);
        pointTimeTextView = itemView.findViewById(R.id.pointTimeTextView);
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
}
