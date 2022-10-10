package ru.kolco24.kolco24.ui.teams;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import ru.kolco24.kolco24.data.Team;
import ru.kolco24.kolco24.databinding.FragmentTeamsBinding;

public class TeamsFragment extends Fragment {
    private FragmentTeamsBinding binding;
    private TeamViewModel mTeamViewModel;
    private SharedPreferences sharedpreferences;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TeamsViewModel teamsViewModel =
                new ViewModelProvider(this).get(TeamsViewModel.class);

        binding = FragmentTeamsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        //pager
        ViewPager2 viewPager = binding.viewPagerTeams;
        viewPager.setAdapter(new CategoriesAdapter(this));

        final TextView textView = binding.textHome;
        teamsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        sharedpreferences = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);
        String team = sharedpreferences.getString("team", "");

        // add team button
        binding.buttonAddTeam.setOnClickListener(view -> {
            Team team2 = new Team(
                    "owner",
                    4F,
                    "6h",
                    "6h",
                    "description",
                    "city",
                    "organization",
                    "2022",
                    "202",
                    1665325248L,
                    1665335248L,
                    true,
                    0
            );
            mTeamViewModel.insert(team2);
            System.out.println("team added");
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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        attachTabToViewPager(binding.tabTeams, binding.viewPagerTeams);
    }

    private void attachTabToViewPager(TabLayout tabLayout, ViewPager2 viewPager) {
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(CategoriesAdapter.getCategoryName(position))
        ).attach();
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