package ru.kolco24.kolco24.ui.teams;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import ru.kolco24.kolco24.databinding.FragmentTeamsBinding;

public class TeamsFragment extends Fragment {
    private FragmentTeamsBinding binding;
    private SharedPreferences sharedpreferences;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TeamsViewModel teamsViewModel =
                new ViewModelProvider(this).get(TeamsViewModel.class);

        binding = FragmentTeamsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textHome;
//        homeViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);
        final EditText editTextTeam = binding.editTextTeam;
        final Button button = binding.button;
        final TextView textViewTeam = binding.textViewTeam;

        sharedpreferences = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);

        String team = sharedpreferences.getString("team", "");
        if (team.isEmpty()) {
            editTextTeam.setVisibility(View.VISIBLE);
            textViewTeam.setVisibility(View.GONE);
            button.setText("Сохранить");
        } else {
            editTextTeam.setText(team);
            textViewTeam.setText(team);
            editTextTeam.setVisibility(View.GONE);
            textViewTeam.setVisibility(View.VISIBLE);
            button.setText("Изменить");
        }

        button.setOnClickListener(view ->  {
            SharedPreferences sp = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);
            String team2 = sp.getString("team", "");
            if (team2.isEmpty()){
                team2 = editTextTeam.getText().toString();
                sp.edit().putString("team", team2).apply();
                editTextTeam.setText(team2);
                textViewTeam.setText(team2);
                editTextTeam.setVisibility(View.GONE);
                textViewTeam.setVisibility(View.VISIBLE);
                button.setText("Изменить");
            } else {
                editTextTeam.setVisibility(View.VISIBLE);
                editTextTeam.setText(team2);
                textViewTeam.setVisibility(View.GONE);
                button.setText("Сохранить");
                sp.edit().remove("team").apply();
            }
        });
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}