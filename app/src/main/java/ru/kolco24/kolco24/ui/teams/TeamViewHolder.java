package ru.kolco24.kolco24.ui.teams;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView teamNumber;
    private int team_id;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        teamNumber = itemView.findViewById(R.id.team_number);
    }

    public void bind(int id, String start_number, String teamname) {
        teamNumber.setText(start_number);
        textView.setText(teamname);
        team_id = id;
        //
        int currentTeam = itemView.getContext().getSharedPreferences(
                "team", Context.MODE_PRIVATE
        ).getInt("team_id", 0);
        if (currentTeam == team_id) {
            itemView.setBackgroundColor(itemView.getResources().getColor(R.color.divider));
        } else {
            itemView.setBackgroundColor(itemView.getResources().getColor(R.color.background));
        }

        itemView.setOnLongClickListener(v -> {
            int prev_team_id = itemView.getContext().getSharedPreferences("team", Context.MODE_PRIVATE)
                    .getInt("team_id", 0);

            if (prev_team_id != team_id) {
                itemView.setBackgroundColor(itemView.getResources().getColor(R.color.myTeam));
                itemView.getContext().getSharedPreferences("team", Context.MODE_PRIVATE)
                        .edit().putInt("team_id", team_id).apply();
                Toast.makeText(itemView.getContext(),
                        "Команда \"" + teamname + "\" выбрана как ваша",
                        Toast.LENGTH_SHORT
                ).show();
                return true;
            }
            return false;
        });
    }

    public static TeamViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }
}
