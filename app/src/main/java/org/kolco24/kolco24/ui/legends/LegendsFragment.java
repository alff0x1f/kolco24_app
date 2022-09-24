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

import org.kolco24.kolco24.data.Point;
import org.kolco24.kolco24.databinding.FragmentLegendsBinding;


import java.io.IOException;

import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
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
        swipeRefreshLayout.setOnRefreshListener(() -> {
            //http
            try {
                run();
            } catch (IOException e) {
                // TODO
                Context context = root.getContext();
                CharSequence text = "Hello toast!";
                int duration = Toast.LENGTH_SHORT;
                Toast toast = Toast.makeText(context, text, duration);
                toast.show();
                //
                e.printStackTrace();
            }
        });

        return root;
    }

    public void run() throws IOException {
        Request request = new Request.Builder()
                .url("http://192.168.88.5:8081")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.out.println("Failure");
                e.printStackTrace();

                SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
                swipeRefreshLayout.setRefreshing(false);

                // toast
                Context context = binding.getRoot().getContext();
                Handler handler = new Handler(context.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        CharSequence text = "Ошибка обновления списка, нет связи с сервером";
                        int duration = Toast.LENGTH_SHORT;
                        Toast toast = Toast.makeText(context, text, duration);
                        toast.show();
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful())
                        throw new IOException("Unexpected code " + response);

                    // turn of loader
                    SwipeRefreshLayout swipeRefreshLayout = binding.swipeToRefresh;
                    swipeRefreshLayout.setRefreshing(false);

                    //print headers to log
                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                    }

                    // insert in DB
                    mPointViewModel.deleteAll();
                    String legend = responseBody.string();
                    String legend_name = legend.substring(0, 100);
                    for (int i = 10; i < 50; i++) {
                        mPointViewModel.insert(new Point(Integer.toString(i), legend_name, 1));
                    }

                    // toast
                    Context context = binding.getRoot().getContext();
                    Handler handler = new Handler(context.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            CharSequence text = "Список обновлен";
                            int duration = Toast.LENGTH_LONG;
                            Toast toast = Toast.makeText(context, text, duration);
                            toast.show();
                        }
                    });
                    // logs
                    System.out.println(legend);
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}