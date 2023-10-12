package ru.kolco24.kolco24.ui.legends;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.ui.members.AddMemberTagActivity;
import ru.kolco24.kolco24.DataDownloader;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.databinding.FragmentLegendsBinding;

public class LegendsFragment extends Fragment implements MenuProvider {

    private FragmentLegendsBinding binding;
    private int teamId;
    private AppDatabase db;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentLegendsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        db = AppDatabase.getDatabase(requireActivity().getApplication());

        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE).getInt("team_id", 0);

        db.photoDao().getCostSum(teamId).observe(getViewLifecycleOwner(), count -> {
            if (count != null) {
                binding.takenPointsSum.setText(String.format("Сумма баллов: %d", count));
            } else {
                binding.takenPointsSum.setText("Сумма баллов: 0");
            }
        });

        // Set up the RecyclerView all points
        final PointListAdapter adapter = new PointListAdapter(new PointListAdapter.PointDiff());
        binding.pointsRecyclerView.setAdapter(adapter);
        binding.pointsRecyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));

        db.pointDao().getPointsByTeam(teamId).observe(getViewLifecycleOwner(), points -> {
            adapter.submitList(points);
            if (points.size() == 0) {
                binding.textNoLegends.setVisibility(View.VISIBLE);
            } else {
                binding.textNoLegends.setVisibility(View.GONE);
                binding.swipeToRefresh.setRefreshing(false);
            }
        });
        //swipe to refresh
        SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
        swipeRefreshLayout.setRefreshing(false);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            DataDownloader dataDownloader = new DataDownloader(
                    requireActivity().getApplication(),
                    () -> swipeRefreshLayout.setRefreshing(false));
            dataDownloader.downloadPoints();
        });

        // Add the MenuProvider to handle menu creation
        requireActivity().addMenuProvider(this, getViewLifecycleOwner());

        return root;
    }

    public void onResume() {
        super.onResume();
        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE).getInt("team_id", 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menu.clear();
        menuInflater.inflate(R.menu.legend_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.action_update) {
            DataDownloader dataDownloader = new DataDownloader(
                    requireActivity().getApplication()
            );
            dataDownloader.downloadPoints();
            return true;
        }
        if (menuItem.getItemId() == R.id.action_local_update) {
            DataDownloader dataDownloader = new DataDownloader(
                    requireActivity().getApplication()
            );
            dataDownloader.setLocalDownload(true);
            dataDownloader.downloadPoints();
            return true;
        }
//        if (menuItem.getItemId() == R.id.add_member_tag) {
//            Intent intent = new Intent(getActivity(), AddMemberTagActivity.class);
//            startActivity(intent);
//            return true;
//        }
        // start SettingsActivity
//        if (menuItem.getItemId() == R.id.action_settings) {
//            Intent intent = new Intent(getActivity(), SettingsActivity.class);
//            startActivity(intent);
//            return true;
//        }
        return false;
    }
}