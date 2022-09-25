package org.kolco24.kolco24.ui.photo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.databinding.FragmentPhotosBinding;

public class PhotoFragment extends Fragment {

    private FragmentPhotosBinding binding;
    private PhotoPointViewModel mPhotoPointViewModel;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        PhotoViewModel takenPointsViewModel =
                new ViewModelProvider(this).get(PhotoViewModel.class);

        binding = FragmentPhotosBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textDashboard;
//        dashboardViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // recycle view
        RecyclerView recyclerView = binding.myPointsRecyclerView;
        final PhotoPointListAdapter adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        // Get a new or existing ViewModel from the ViewModelProvider.
        mPhotoPointViewModel = new ViewModelProvider(this).get(PhotoPointViewModel.class);
        mPhotoPointViewModel.getAllPhotoPoints().observe(getViewLifecycleOwner(), adapter::submitList);

        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}