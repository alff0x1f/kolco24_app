package ru.kolco24.kolco24.ui.teams;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.io.Console;

import ru.kolco24.kolco24.databinding.FragmentTeamsBinding;

public class TeamsFragment extends Fragment {
    private FragmentTeamsBinding binding;
    private SharedPreferences sharedpreferences;
    EditText editTeamField;

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


        button.setOnClickListener(view -> {
            editTeamField = new EditText(this.getContext());
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle("Title")
                    .setMessage("Message")
                    .setView(editTeamField)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            String editTextInput = editTeamField.getText().toString();
                            System.out.println("editext value is: "+ editTextInput);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.show();

            SharedPreferences sp = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);
            String team2 = sp.getString("team", "");
            if (team2.isEmpty()) {
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

        // QR code

        binding.fabQr.setOnClickListener(view -> {
            try {
                Intent intent = new Intent("com.google.zxing.client.android.SCAN");
                intent.putExtra("SCAN_MODE", "QR_CODE_MODE"); // "PRODUCT_MODE for bar codes
                startActivityForResult(intent, 0);

            } catch (Exception e) {
                Uri marketUri = Uri.parse("market://details?id=com.srowen.bs.android");
                Intent marketIntent = new Intent(Intent.ACTION_VIEW, marketUri);
                startActivity(marketIntent);
            }
        });

        return root;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode == RESULT_OK) {
            String contents = data.getStringExtra("SCAN_RESULT");
            String[] qr_content = contents.split(":");
            if (qr_content.length != 3 || !qr_content[0].equals("t") || !qr_content[1].equals("2022")) {
                Toast.makeText(getActivity(), "Неверный QR код", Toast.LENGTH_LONG).show();
                return;
            }
            String team = qr_content[2];
            int team_number = Integer.parseInt(team);

            Toast toast = Toast.makeText(
                    getContext(),
                    String.format("Команда %d", team_number),
                    Toast.LENGTH_LONG
            );
            toast.show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}