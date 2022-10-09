package ru.kolco24.kolco24.ui.teams;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
    }

    public void bind() {
        textView.setText("Team");
    }

    public static TeamViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }
}
