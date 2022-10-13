package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import ru.kolco24.kolco24.databinding.FragmentDemoObjectBinding;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link DemoObjectFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class DemoObjectFragment extends Fragment {
    private FragmentDemoObjectBinding binding;
    private TeamViewModel teamViewModel;

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    public static final String CATEGORY_NAME = "6ч";
    public static final String CATEGORY_CODE = "6h";

    // TODO: Rename and change types of parameters
    private String categoryName;
    private String categoryCode;

    public DemoObjectFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment DemoObjectFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static DemoObjectFragment newInstance(String param1, String param2) {
        DemoObjectFragment fragment = new DemoObjectFragment();
        Bundle args = new Bundle();
        args.putString(CATEGORY_NAME, param1);
        args.putString(CATEGORY_CODE, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            categoryName = getArguments().getString(CATEGORY_NAME);
            categoryCode = getArguments().getString(CATEGORY_CODE);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentDemoObjectBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        // recycler view
        RecyclerView recyclerTeams = binding.recyclerTeams;
        final TeamListAdapter adapter = new TeamListAdapter(new TeamListAdapter.TeamDiff());
        recyclerTeams.setAdapter(adapter);
        recyclerTeams.setLayoutManager(new LinearLayoutManager(getContext()));

        teamViewModel = new ViewModelProvider(this).get(TeamViewModel.class);
        teamViewModel.getTeamsByCategory(categoryCode).observe(getViewLifecycleOwner(), adapter::submitList);
        teamViewModel.getTeamsByCategory(categoryCode).observe(getViewLifecycleOwner(), teams -> {
            if (teams.size() == 0) {
                binding.textNoTeams.setVisibility(View.VISIBLE);
            } else {
                binding.textNoTeams.setVisibility(View.GONE);
                binding.swipeToRefresh.setRefreshing(false);
            }
        });
        binding.swipeToRefresh.setOnRefreshListener(() -> teamViewModel.downloadTeams(
                "https://kolco24.ru/api/v1/teams", categoryCode
        ));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
//        TextView textView = view.findViewById(R.id.text);
//        textView.setText(mParam1);
        //toast observer
        teamViewModel.getToastMessage().observe(getViewLifecycleOwner(), s -> {
            System.out.println("toast message: " + s);
            if (s != null) {
                Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
                teamViewModel.clearToastMessage();
            }
        });
        teamViewModel.isLoading().observe(getViewLifecycleOwner(), aBoolean -> {
            binding.swipeToRefresh.setRefreshing(aBoolean);
        });
        //
    }

    @Override
    public void onResume() {
        super.onResume();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle("Команды " + categoryName);
    }
}