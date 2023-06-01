package ru.kolco24.kolco24.ui.legends;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.kolco24.kolco24.data.Point;
import ru.kolco24.kolco24.databinding.FragmentLegendsBinding;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.ui.photo.NewPhotoActivity;
import ru.kolco24.kolco24.ui.photo.PhotoViewModel;

public class LegendsFragment extends Fragment {

    private FragmentLegendsBinding binding;
    private PointViewModel mPointViewModel;
    private PhotoViewModel mPhotoViewModel;
    private int teamId;
    public final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloadPoints();
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
        //http
        swipeRefreshLayout.setOnRefreshListener(this::downloadPoints);

        return root;
    }

    public void downloadPoints() {
        Request request = new Request.Builder()
                .url("https://kolco24.ru/api/v1/points")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.out.println("Failure");
                e.printStackTrace();
                offSwipeRefreshLayout();
                toast("Ошибка обновления списка, нет связи с сервером");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    // turn of loader
                    offSwipeRefreshLayout();

                    if (!response.isSuccessful()) {
                        toast("Ошибка " + response.code());
                        throw new IOException("Unexpected code " + response);
                    }

                    //print headers to log
                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                    }

                    // insert in DB
                    String legend = responseBody.string();

                    try {
                        JSONArray jObj = new JSONArray(legend);
                        Boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject point = jObj.getJSONObject(i);

                            int number = point.getInt("number");
                            String description = point.getString("description");
                            int cost = point.getInt("cost");

                            Point existPoint = mPointViewModel.getPointByNumber(number);
                            if (existPoint == null) {
                                mPointViewModel.insert(new Point(
                                        number,
                                        description,
                                        cost
                                ));
                                isUpdated = true;
                            } else {
                                if (!existPoint.mDescription.equals(description) ||
                                        existPoint.mCost != cost) {
                                    existPoint.mDescription = description;
                                    existPoint.mCost = cost;
                                    mPointViewModel.update(existPoint);
                                    isUpdated = true;
                                }
                            }

                        }
                        if (isUpdated) {
                            toast("Список обновлен");
                        }
                    } catch (JSONException e) {
                        toast("Ошибка декодирования JSON");
                        e.printStackTrace();
                        throw new IOException("Wrong JSON");
                    }
                    // logs
                    System.out.println(legend);
                }
            }

            public void toast(String text) {
                if (binding == null) {
                    return;
                }
                Context context = binding.getRoot().getContext();
                Handler handler = new Handler(context.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        int duration = Toast.LENGTH_SHORT;
                        Toast toast = Toast.makeText(context, text, duration);
                        toast.show();
                    }
                });
            }

            public void offSwipeRefreshLayout() {
                if (binding == null) {
                    return;
                }
                SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
                swipeRefreshLayout.setRefreshing(false);
            }
        });
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