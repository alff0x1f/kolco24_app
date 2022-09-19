package org.kolco24.kolco24.ui.legends;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.R;
import org.kolco24.kolco24.data.PointInfo;

public class PointViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView textDesctiption;
    private final TextView textCost;

    /*__init__*/
    private PointViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        textDesctiption = itemView.findViewById(R.id.textDescription);
        textCost = itemView.findViewById(R.id.textCost);
    }

    public void bind(PointInfo point) {
        textView.setText(point.getName());
        textDesctiption.setText(point.getDescription());
        textCost.setText(String.valueOf(point.getCost()));
    }

    /**
     * Creates a new ViewHolder.
     *
     * @param parent The ViewGroup into which the new View will be added
     * after it is bound to an adapter position.
     * @return A new ViewHolder that holds a View of the given view type.
     */
    static PointViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.recyclerview_item, parent, false);
        return new PointViewHolder(view);
    }
}
