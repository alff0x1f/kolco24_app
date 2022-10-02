package ru.kolco24.kolco24.ui.legends;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

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

    public void bind(Point point) {
        textView.setText(String.format("%02d", point.mNumber));
        textPointNumber.setText(String.format("%02d", point.mNumber));

        textDescription.setText(point.mDescription);
        textCost.setText(String.format("+%d", point.mCost));
        Drawable drawable = ContextCompat.getDrawable(itemView.getContext(), R.drawable.cost_background);
        // set color based on cost
        int color = Color.parseColor("#000000");
        if (point.mCost == 1) {
            color = itemView.getResources().getColor(R.color.level1);
        } else if (point.mCost == 2) {
            color = itemView.getResources().getColor(R.color.level2);
        } else if (point.mCost == 3) {
            color = itemView.getResources().getColor(R.color.level3);
        } else if (point.mCost == 4) {
            color = itemView.getResources().getColor(R.color.level4);
        } else if (point.mCost == 5) {
            color = itemView.getResources().getColor(R.color.level5);
        } else if (point.mCost == 6) {
            color = itemView.getResources().getColor(R.color.level6);
        } else if (point.mCost == 7) {
            color = itemView.getResources().getColor(R.color.level7);
        } else if (point.mCost == 8) {
            color = itemView.getResources().getColor(R.color.level8);
        } else if (point.mCost == 9) {
            color = itemView.getResources().getColor(R.color.level9);
        } else {
            color = itemView.getResources().getColor(R.color.level10);
        }
        drawable.mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP);
        textCost.setBackground(drawable);
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
