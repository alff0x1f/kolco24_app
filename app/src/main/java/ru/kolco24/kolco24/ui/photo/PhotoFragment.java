package ru.kolco24.kolco24.ui.photo;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.Photo;
import ru.kolco24.kolco24.databinding.FragmentPhotosBinding;

public class PhotoFragment extends Fragment {
    private final int LOCAL_SYNC = 1;
    private final int INTERNET_SYNC = 2;

    private FragmentPhotosBinding binding;
    private PhotoViewModel mPhotoViewModel;
    private final PhotoPointListAdapter adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
    private int teamId;

    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        PhotoViewModel takenPointsViewModel =
                new ViewModelProvider(this).get(PhotoViewModel.class);

        binding = FragmentPhotosBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        //
        teamId = requireContext().getSharedPreferences(
                "team",
                Context.MODE_PRIVATE
        ).getInt("team_id", 0);

        // recycle view
        RecyclerView recyclerView = binding.myPointsRecyclerView;
//        adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(3, 8, false));
        // Get a new or existing ViewModel from the ViewModelProvider.
        mPhotoViewModel = new ViewModelProvider(this).get(PhotoViewModel.class);
        mPhotoViewModel.getPhotoByTeamId(teamId).observe(getViewLifecycleOwner(), adapter::submitList);
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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPhotoViewModel.getPhotoCount(teamId).observe(getViewLifecycleOwner(), count -> {
            binding.textDashboard.setText(String.format("Количество КП: %d", count));
        });
        mPhotoViewModel.getCostSum(teamId).observe(getViewLifecycleOwner(), sum -> {
            if (sum == null) {
                binding.textDashboard2.setText("Сумма баллов: 0");
            } else {
                binding.textDashboard2.setText(String.format("Сумма баллов: %d", sum));
            }
        });
        mPhotoViewModel.getTeamName(teamId).observe(getViewLifecycleOwner(), name -> {
            if (name != null) {
                binding.teamName.setText(String.format("Команда: %s", name));
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.photos_menu, menu);
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sync_internet) {
            uploadPhotos(INTERNET_SYNC);
        }
        if (item.getItemId() == R.id.action_sync_local) {
            uploadPhotos(LOCAL_SYNC);
        }
        return super.onOptionsItemSelected(item);
    }

    // on update
    @Override
    public void onResume() {
        super.onResume();
        teamId = getContext().getSharedPreferences("team", Context.MODE_PRIVATE
        ).getInt("team_id", 0);
    }

    private void uploadPhotos(int type) {
        AsyncTask.execute(() -> {
            String url;
            if (type == LOCAL_SYNC) {
                url = "http://192.168.88.164/api/v1/upload_photo";
            } else {
                url = "https://kolco24.ru/api/v1/upload_photo";
            }
            try {
                List<Photo> photos;
                if (type == LOCAL_SYNC) {
                    photos = mPhotoViewModel.getNotLocalSyncPhoto(teamId);
                } else {
                    photos = mPhotoViewModel.getNotSyncPhoto(teamId);
                }

                OkHttpClient client = new OkHttpClient();
                for (Photo photo : photos) {
                    File file = new File(photo.photo_url);
                    RequestBody requestBody = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("team_id", String.valueOf(teamId))
                            .addFormDataPart("point_number", String.valueOf(photo.getPointNumber()))
                            .addFormDataPart("photo", file.getName(), RequestBody.create(file, MediaType.parse("image/*")))
                            .build();
                    Request request = new Request.Builder().url(url).post(requestBody).build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        try {
                            JSONObject jsonObject = new JSONObject(response.body().string());
                            if (jsonObject.getBoolean("success")) {
                                photo.setSync(true);
                                mPhotoViewModel.update(photo);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        // Ui tread
                        String err_text = String.format("Ошибка при отправке фото КП %d", photo.point_number);
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), err_text, Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "onCreateView: " + e.getMessage());
            }
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