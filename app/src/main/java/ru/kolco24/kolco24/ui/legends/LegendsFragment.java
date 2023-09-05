package ru.kolco24.kolco24.ui.legends;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import ru.kolco24.kolco24.PointDownloader;
import ru.kolco24.kolco24.databinding.FragmentLegendsBinding;
import ru.kolco24.kolco24.ui.photo.NewPhotoActivity;
import ru.kolco24.kolco24.ui.photo.PhotoViewModel;

public class LegendsFragment extends Fragment {

    private FragmentLegendsBinding binding;
    private PointViewModel mPointViewModel;
    private PhotoViewModel mPhotoViewModel;
    private int teamId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        LegendsViewModel legendsViewModel =
                new ViewModelProvider(this).get(LegendsViewModel.class);

        binding = FragmentLegendsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

//        final TextView textView = binding.legendHeader;
//        legendsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);
        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE).getInt("team_id", 0);
        mPointViewModel = new ViewModelProvider(this).get(PointViewModel.class);
        mPhotoViewModel = new ViewModelProvider(this).get(PhotoViewModel.class);

        mPhotoViewModel.getCostSum(teamId).observe(getViewLifecycleOwner(), count -> {
            if (count != null) {
                binding.takenPointsSum.setText(String.format("Сумма баллов: %d", count));
            } else {
                binding.takenPointsSum.setText("Сумма баллов: 0");
            }
        });
        mPhotoViewModel.getPhotoCount(teamId).observe(getViewLifecycleOwner(), count -> {
            if (count == 0) {
                binding.takenPointsSum.setVisibility(View.GONE);
                binding.header1.setVisibility(View.GONE);
                binding.header2.setVisibility(View.GONE);
            } else {
                binding.takenPointsSum.setVisibility(View.VISIBLE);
                binding.header1.setVisibility(View.VISIBLE);
                binding.header2.setVisibility(View.VISIBLE);
            }
        });

        // Set up the RecyclerView taken points
        RecyclerView recyclerView = binding.recyclerView;
        final PointListAdapter adapter = new PointListAdapter(new PointListAdapter.PointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));
        // Get a new or existing ViewModel from the ViewModelProvider.
        mPointViewModel.getTakenPointsByTeam(teamId).observe(getViewLifecycleOwner(), adapter::submitList);

        // Set up the RecyclerView new points
        RecyclerView newPointsRecyclerView = binding.newPointsRecyclerView;
        final PointListAdapter adapter2 = new PointListAdapter(new PointListAdapter.PointDiff());
        newPointsRecyclerView.setAdapter(adapter2);
        newPointsRecyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));
        mPointViewModel.getNewPointsByTeam(teamId).observe(getViewLifecycleOwner(), adapter2::submitList);

        mPointViewModel.getAllPoints().observe(getViewLifecycleOwner(), points -> {
            if (points.size() == 0) {
                binding.textNoLegends.setVisibility(View.VISIBLE);
            } else {
                binding.textNoLegends.setVisibility(View.GONE);
                binding.swipeToRefresh.setRefreshing(false);
            }
        });
        //fab
        FloatingActionButton fab = binding.fab;
        fab.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
        });
        //swipe to refresh
        SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
        swipeRefreshLayout.setRefreshing(false);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            PointDownloader pointDownloader = new PointDownloader(
                    requireActivity().getApplication(),
                    () -> swipeRefreshLayout.setRefreshing(false));
            pointDownloader.downloadPoints();
        });

        return root;
    }

    //set background recycle items on resume
    public void onResume() {
        super.onResume();
        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE).getInt("team_id", 0);
        if (binding != null) {
            //background each item of recycle view
            RecyclerView recyclerView = binding.newPointsRecyclerView;
            Drawable defaultBackgroundColor = recyclerView.getBackground();
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                View view = recyclerView.getChildAt(i);
                view.setBackground(defaultBackgroundColor);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}