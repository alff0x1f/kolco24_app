package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import ru.kolco24.kolco24.R;
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
    public static final String ARG_PARAM1 = "param1";
    public static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

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
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
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
        teamViewModel.getTeamsByCategory(mParam2).observe(getViewLifecycleOwner(), adapter::submitList);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
//        TextView textView = view.findViewById(R.id.text);
//        textView.setText(mParam1);
    }
}