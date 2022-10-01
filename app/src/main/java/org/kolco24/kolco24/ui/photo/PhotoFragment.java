package org.kolco24.kolco24.ui.photo;

import android.content.Intent;
import android.graphics.Rect;
import android.os.AsyncTask;
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
import org.kolco24.kolco24.databinding.FragmentPhotosBinding;

public class PhotoFragment extends Fragment {
    private FragmentPhotosBinding binding;
    private PhotoViewModel mPhotoViewModel;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        PhotoViewModel takenPointsViewModel =
                new ViewModelProvider(this).get(PhotoViewModel.class);

        binding = FragmentPhotosBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

//        final TextView textView = binding.textDashboard;
//        dashboardViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // recycle view
        RecyclerView recyclerView = binding.myPointsRecyclerView;
        final PhotoPointListAdapter adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(3, 8, false));
        // Get a new or existing ViewModel from the ViewModelProvider.
        mPhotoViewModel = new ViewModelProvider(this).get(PhotoViewModel.class);
        mPhotoViewModel.getAllPhoto().observe(getViewLifecycleOwner(), adapter::submitList);
        //counters
        updateCounters();
        //fab
        FloatingActionButton fab = binding.fab;
        fab.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
//            NavHostFragment.findNavController(this).navigate(R.id.action_navigation_taken_points_to_navigation_new_photo);
        });

        return root;
    }

    // on update
    @Override
    public void onResume() {
        super.onResume();
        updateCounters();
    }

    public void updateCounters() {
        // update photo counter
        AsyncTask.execute(() -> {
            final TextView textView = binding.textDashboard;
            int count = mPhotoViewModel.getPhotoCount();
            textView.post(() -> textView.setText(String.format("Количество КП: %d", count)));

            final TextView textView1 = binding.textDashboard2;
            int sum = mPhotoViewModel.getCostSum();
            textView1.post(() -> textView1.setText(String.format("Сумма КП: %d", sum)));
        });
    }

    public static class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {

        private int spanCount;
        private int spacing;
        private boolean includeEdge;

        public GridSpacingItemDecoration(int spanCount, int spacing, boolean includeEdge) {
            this.spanCount = spanCount;
            this.spacing = spacing;
            this.includeEdge = includeEdge;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view); // item position
            int column = position % spanCount; // item column

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount; // spacing - column * ((1f / spanCount) * spacing)
                outRect.right = (column + 1) * spacing / spanCount; // (column + 1) * ((1f / spanCount) * spacing)

                if (position < spanCount) { // top edge
                    outRect.top = spacing;
                }
                outRect.bottom = spacing; // item bottom
            } else {
                outRect.left = column * spacing / spanCount; // column * ((1f / spanCount) * spacing)
                outRect.right = spacing - (column + 1) * spacing / spanCount; // spacing - (column + 1) * ((1f /    spanCount) * spacing)
                if (position >= spanCount) {
                    outRect.top = spacing; // item top
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}