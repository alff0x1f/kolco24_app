package ru.kolco24.kolco24.ui.photo;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kolco24.kolco24.NewPhotoActivity;
import ru.kolco24.kolco24.data.Photo;
import ru.kolco24.kolco24.databinding.FragmentPhotosBinding;

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
        // QR code
        binding.buttonQr.setOnClickListener(v -> {
            try {

                Intent intent = new Intent("com.google.zxing.client.android.SCAN");
                intent.putExtra("SCAN_MODE", "QR_CODE_MODE"); // "PRODUCT_MODE for bar codes

                startActivityForResult(intent, 0);

            } catch (Exception e) {
                Uri marketUri = Uri.parse("market://details?id=com.srowen.bs.android");
                Intent marketIntent = new Intent(Intent.ACTION_VIEW, marketUri);
                startActivity(marketIntent);

            }
        });
        // send photos
        binding.buttonSendPhotos.setOnClickListener(v -> {
            AsyncTask.execute(() -> {
                try {
                    Photo photo = mPhotoViewModel.getPhotoById(1);
                    OkHttpClient client = new OkHttpClient();
                    File file = new File(photo.photo_url);
                    RequestBody requestBody = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", file.getName(), RequestBody.create(file, MediaType.parse("image/*")))
                            .addFormDataPart("team_id", "1")
                            .build();
                    Request request = new Request.Builder()
                            .url("http://192.168.88.164:8000/api/v1/upload_photo")
                            .post(requestBody)
                            .build();
                    Response response = client.newCall(request).execute();
                    Log.d(TAG, "onCreateView: " + response.body().string());
                } catch (Exception e) {
                    Log.d(TAG, "onCreateView: " + e.getMessage());
                }
            });
        });
        //fab
        FloatingActionButton fab = binding.fab;
        fab.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
//            NavHostFragment.findNavController(this).navigate(R.id.action_navigation_taken_points_to_navigation_new_photo);
        });

        return root;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0) {
            if (resultCode == RESULT_OK) {
                String contents = data.getStringExtra("SCAN_RESULT");
                Toast toast = Toast.makeText(getContext(), contents, Toast.LENGTH_LONG);
                toast.show();
            }
            if(resultCode == RESULT_CANCELED){
                //handle cancel
            }
        }
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
            textView1.post(() -> textView1.setText(String.format("Сумма баллов: %d", sum)));
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

    public static JSONObject uploadImage(File file) {

        try {

            final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/jpg");

            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("userid", "8457851245")
                    .addFormDataPart(
                            "userfile",
                            "profile.png",
                            RequestBody.create(file, MEDIA_TYPE_PNG)
                    )
                    .build();

            Request request = new Request.Builder()
                    .url("url")
                    .post(body)
                    .build();

            OkHttpClient client = new OkHttpClient();
            Response response = client.newCall(request).execute();

            Log.d("response", "uploadImage:" + response.body().string());

            return new JSONObject(response.body().string());

        } catch (UnknownHostException | UnsupportedEncodingException e) {
            Log.e(TAG, "Error: " + e.getLocalizedMessage());
        } catch (Exception e) {
            Log.e(TAG, "Other Error: " + e.getLocalizedMessage());
        }
        return null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}