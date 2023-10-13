package ru.kolco24.kolco24.ui.teams;

import static android.app.Activity.RESULT_OK;

import static kotlinx.coroutines.CoroutineScopeKt.CoroutineScope;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import ru.kolco24.kolco24.DataDownloader;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.databinding.FragmentTeamsBinding;

public class TeamsFragment extends Fragment implements MenuProvider {
    private FragmentTeamsBinding binding;
    private TeamViewModel mTeamViewModel;
    private SharedPreferences sharedpreferences;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TeamsViewModel teamsViewModel =
                new ViewModelProvider(this).get(TeamsViewModel.class);

        mTeamViewModel = new ViewModelProvider(this).get(TeamViewModel.class);

        binding = FragmentTeamsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        //pager
        ViewPager2 viewPager = binding.viewPagerTeams;
        viewPager.setAdapter(new CategoriesAdapter(this));

//        final TextView textView = binding.textHome;
//        teamsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        sharedpreferences = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);
        String team = sharedpreferences.getString("team", "");

        // QR code
//        binding.fabQr.setOnClickListener(this::onClick);
        binding.fabQr.setVisibility(View.GONE);

        // Add the MenuProvider to handle menu creation
        requireActivity().addMenuProvider(this, getViewLifecycleOwner());

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        attachTabToViewPager(binding.tabTeams, binding.viewPagerTeams);
        //toast observer
        mTeamViewModel.getToastMessage().observe(getViewLifecycleOwner(), s -> {
            System.out.println("toast message: " + s);
            if (s != null) {
                Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
                mTeamViewModel.clearToastMessage();
            }
        });
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

            @SuppressLint("DefaultLocale") Toast toast = Toast.makeText(
                    getContext(),
                    String.format("Команда %d", team_number),
                    Toast.LENGTH_LONG
            );
            toast.show();
        }
    }

    // Implement the MenuProvider interface
    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        menu.clear(); // Clear the menu before inflating it
        inflater.inflate(R.menu.team_menu, menu);

    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.action_update) {
            DataDownloader dataDownloader = new DataDownloader(
                    requireActivity().getApplication()
            );
            dataDownloader.downloadTeams(null);
            return true;
        } else if (menuItem.getItemId() == R.id.action_local_update) {
            DataDownloader dataDownloader = new DataDownloader(
                    requireActivity().getApplication()
            );
            dataDownloader.setLocalDownload(true);
            dataDownloader.downloadTeams(null);
            return true;
        } else if (menuItem.getItemId() == R.id.action_upload_times) {
            Executor executor = Executors.newSingleThreadExecutor();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    DataDownloader dataDownloader = new DataDownloader(
                            requireActivity().getApplication()
                    );
                    dataDownloader.setLocalDownload(true);
                    dataDownloader.uploadTeamsTimes();
                }
            });
            return true;
        }
        return false;
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void onClick(View view) {
        try {
            Intent intent = new Intent("com.google.zxing.client.android.SCAN");
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE"); // "PRODUCT_MODE for bar codes
            startActivityForResult(intent, 0);

        } catch (Exception e) {
            try {
                Uri marketUri = Uri.parse("market://details?id=com.google.zxing.client.android");
                Intent marketIntent = new Intent(Intent.ACTION_VIEW, marketUri);
                startActivity(marketIntent);
            } catch (ActivityNotFoundException e1) {
                Toast.makeText(getActivity(), "Сканирование QR недоступно", Toast.LENGTH_LONG).show();
            }
        }
    }
}