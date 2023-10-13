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

import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.recyclerview.widget.RecyclerView;

import org.w3c.dom.Text;

import java.util.Date;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import ru.kolco24.kolco24.DataDownloader;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.ui.StartFinishActivity;
import ru.kolco24.kolco24.ui.photo.NewPhotoActivity;

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView teamNumber;
    private final TextView paidPeople;
    private final TextView teamPlace;
    private final TextView start_time;
    private final TextView finish_time;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        teamNumber = itemView.findViewById(R.id.team_number);
        paidPeople = itemView.findViewById(R.id.paid_people);
        teamPlace = itemView.findViewById(R.id.team_place);
        start_time = itemView.findViewById(R.id.start_time);
        finish_time = itemView.findViewById(R.id.finish_time);
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

        if (team.getStartTime() != 0) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            Date currentTime = new Date(System.currentTimeMillis());
            start_time.setText(dateFormat.format(currentTime));
        } else {
            start_time.setText("");
        }

        if (team.getFinishTime() != 0) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            Date currentTime = new Date(System.currentTimeMillis());
            finish_time.setText(dateFormat.format(currentTime));
        } else {
            finish_time.setText("");
        }
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
