package ru.kolco24.kolco24.ui.teams;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Team;

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView teamNumber;
    private final TextView paidPeople;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        teamNumber = itemView.findViewById(R.id.team_number);
        paidPeople = itemView.findViewById(R.id.paid_people);
    }

    public void bind(Team team) {
        teamNumber.setText(team.start_number);
        textView.setText(team.teamname);
        paidPeople.setText(String.format("%.0f чел", team.getPaidPeople()));
        //
        int currentTeam = itemView.getContext().getSharedPreferences(
                "team", Context.MODE_PRIVATE
        ).getInt("team_id", 0);
        if (currentTeam == team.getId()) {
            itemView.setBackgroundColor(itemView.getResources().getColor(R.color.divider));
        } else {
            itemView.setBackgroundColor(itemView.getResources().getColor(R.color.background));
        }

        itemView.setOnClickListener(view -> {
            AlertDialog dialog = new AlertDialog.Builder(itemView.getContext())
                    .setTitle(team.teamname)
                    .setMessage("Эта ваша команда?")
                    .setPositiveButton("Да", (dialogInterface, i) -> {
                        itemView.getContext().getSharedPreferences("team", Context.MODE_PRIVATE)
                                .edit().putInt("team_id", team.getId()).apply();
                        Toast.makeText(itemView.getContext(),
                                "Команда \"" + team.teamname + "\" выбрана как ваша",
                                Toast.LENGTH_SHORT
                        ).show();
                    })
                    .setNegativeButton("Нет", (dialogInterface, i) -> {
                    })
                    .setOnCancelListener(dialogInterface -> {
                    })
                    .create();
            dialog.show();
        });
    }

    public static TeamViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }
}
