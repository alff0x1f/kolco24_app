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

public class TeamViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;
    private final TextView teamNumber;
    private final TextView paidPeople;
    private int team_id;

    /*__init__*/
    private TeamViewHolder(View itemView) {
        super(itemView);
        textView = itemView.findViewById(R.id.textView);
        teamNumber = itemView.findViewById(R.id.team_number);
        paidPeople = itemView.findViewById(R.id.paid_people);
    }

    public void bind(int id, String start_number, String teamname, Float paid_people) {
//        itemView.findViewById(R.id.team_stat).setVisibility(View.GONE);
        teamNumber.setText(start_number);
        textView.setText(teamname);
        paidPeople.setText(String.format("%.0f чел", paid_people));
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

        itemView.setOnClickListener(view -> {
            AlertDialog dialog = new AlertDialog.Builder(itemView.getContext())
                    .setTitle(teamname)
                    .setMessage("Эта ваша команда?")
                    .setPositiveButton("Да", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            itemView.getContext().getSharedPreferences("team", Context.MODE_PRIVATE)
                                    .edit().putInt("team_id", team_id).apply();
                            Toast.makeText(itemView.getContext(),
                                    "Команда \"" + teamname + "\" выбрана как ваша",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }).setNegativeButton(
                            "Нет", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {

                                }
                            }
                    )
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialogInterface) {

                        }
                    }).create();
            dialog.show();
        });
    }

    public static TeamViewHolder create(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }
}
