package org.kolco24.kolco24.ui.taken_points;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.kolco24.kolco24.databinding.FragmentTakenPointsBinding;

public class TakenPointsFragment extends Fragment {

    private FragmentTakenPointsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TakenPointsViewModel takenPointsViewModel =
                new ViewModelProvider(this).get(TakenPointsViewModel.class);

        binding = FragmentTakenPointsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textDashboard;
//        dashboardViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}