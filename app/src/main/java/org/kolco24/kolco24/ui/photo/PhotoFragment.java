package org.kolco24.kolco24.ui.photo;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.kolco24.kolco24.NewPhotoActivity;
import org.kolco24.kolco24.data.Photo;
import org.kolco24.kolco24.databinding.FragmentPhotosBinding;

public class PhotoFragment extends Fragment {
    public static final int NEW_PHOTO_ACTIVITY_REQUEST_CODE = 1;

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
        //fab
        FloatingActionButton fab = binding.fab;
        fab.setOnClickListener( view -> {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivityForResult(intent, NEW_PHOTO_ACTIVITY_REQUEST_CODE);
//            NavHostFragment.findNavController(this).navigate(R.id.action_navigation_taken_points_to_navigation_new_photo);
        });

        return root;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NEW_PHOTO_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            Photo photo = new Photo(
                    1,
                    data.getStringExtra(NewPhotoActivity.PHOTO_URI),
                    data.getStringExtra(NewPhotoActivity.POINT_NAME)
            );
            mPhotoPointViewModel.insert(photo);
        }
    }

    @Override
    public void onDestroyView() {
        mPhotoPointViewModel.deleteAll();
        super.onDestroyView();
        binding = null;
    }
}