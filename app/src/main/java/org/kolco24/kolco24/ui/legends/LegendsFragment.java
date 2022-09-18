package org.kolco24.kolco24.ui.legends;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.kolco24.kolco24.databinding.FragmentLegendsBinding;

public class LegendsFragment extends Fragment {

    private FragmentLegendsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        LegendsViewModel legendsViewModel =
                new ViewModelProvider(this).get(LegendsViewModel.class);

        binding = FragmentLegendsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.legendHeader;
        legendsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        RecyclerView recyclerView = binding.recyclerView;
        final PointListAdapter adapter = new PointListAdapter(new PointListAdapter.PointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));

        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}