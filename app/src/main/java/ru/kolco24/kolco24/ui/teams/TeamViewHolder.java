package ru.kolco24.kolco24.ui.teams;

import static androidx.core.content.ContextCompat.startActivity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.ui.StartFinishActivity;
import ru.kolco24.kolco24.ui.photo.NewPhotoActivity;

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView teamNumber;
    private final TextView paidPeople;
    private final TextView teamPlace;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        teamNumber = itemView.findViewById(R.id.team_number);
        paidPeople = itemView.findViewById(R.id.paid_people);
        teamPlace = itemView.findViewById(R.id.team_place);
    }

    public void bind(Team team) {
        teamNumber.setText(team.getStartNumber());
        textView.setText(team.getTeamname());
        if (team.getPlace() > 0) {
            teamPlace.setText(String.format("%d", team.getPlace()));
        } else {
            teamPlace.setText("-");
        }
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
            Intent intent = new Intent(itemView.getContext(), StartFinishActivity.class);
            intent.putExtra("teamId", team.getId());
            itemView.getContext().startActivity(intent);
        });
    }

    public static TeamViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }
}
