package org.kolco24.kolco24.ui.legends;

import android.content.Context;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kolco24.kolco24.data.Point;
import org.kolco24.kolco24.databinding.FragmentLegendsBinding;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LegendsFragment extends Fragment {

    private FragmentLegendsBinding binding;
    private PointViewModel mPointViewModel;
    private final OkHttpClient client = new OkHttpClient();

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        LegendsViewModel legendsViewModel =
                new ViewModelProvider(this).get(LegendsViewModel.class);

        binding = FragmentLegendsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

//        final TextView textView = binding.legendHeader;
//        legendsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        RecyclerView recyclerView = binding.recyclerView;
        final PointListAdapter adapter = new PointListAdapter(new PointListAdapter.PointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));

        // Get a new or existing ViewModel from the ViewModelProvider.
        mPointViewModel = new ViewModelProvider(this).get(PointViewModel.class);
        mPointViewModel.getAllPoints().observe(getViewLifecycleOwner(), adapter::submitList);

        //swipe to refresh
        SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
        //http
        swipeRefreshLayout.setOnRefreshListener(this::downloadPoints);

        return root;
    }

    public void downloadPoints() {
        Request request = new Request.Builder()
                .url("http://192.168.88.164:8000/api/v1/points")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.out.println("Failure");
                e.printStackTrace();

                SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
                swipeRefreshLayout.setRefreshing(false);

                // toast
                toast("Ошибка обновления списка, нет связи с сервером");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    // turn of loader
                    SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
                    swipeRefreshLayout.setRefreshing(false);

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
                    mPointViewModel.deleteAll();
                    String legend = responseBody.string();

                    try {
                        JSONArray jObj = new JSONArray(legend);
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject point = jObj.getJSONObject(i);
                            Point p = new Point(
                                    point.getString("number"),
                                    point.getString("description"),
                                    point.getInt("cost")
                            );
                            mPointViewModel.insert(p);
                        }
                        toast("Список обновлен");
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
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}