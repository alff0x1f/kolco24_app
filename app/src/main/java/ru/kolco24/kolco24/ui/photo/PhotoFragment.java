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
import ru.kolco24.kolco24.ui.teams.TeamViewModel;

public class PhotoFragment extends Fragment {
    private final int LOCAL_SYNC = 1;
    private final int INTERNET_SYNC = 2;
    public final OkHttpClient client = new OkHttpClient();

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
        mPhotoViewModel.getPhotoByTeamId(teamId).observe(getViewLifecycleOwner(), photos -> {
            uploadPhotos(false);
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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPhotoViewModel.getPhotoCount(teamId).observe(getViewLifecycleOwner(), count -> {
            binding.textDashboard.setText(String.format("???????????????????? ????: %d", count));
        });
        mPhotoViewModel.getCostSum(teamId).observe(getViewLifecycleOwner(), sum -> {
            if (sum == null) {
                binding.textDashboard2.setText("?????????? ????????????: 0");
            } else {
                binding.textDashboard2.setText(String.format("?????????? ????????????: %d", sum));
            }
        });
        mPhotoViewModel.getTeam(teamId).observe(getViewLifecycleOwner(), team -> {
            binding.teamName.setText(String.format("%s: %s", team.start_number, team.teamname));
        });
        mPhotoViewModel.getNonLegendPointNumbers(teamId).observe(getViewLifecycleOwner(), nums -> {
            if (nums != null && nums.size() > 0) {
                binding.warning.setVisibility(View.VISIBLE);
                StringBuilder pointsStr = new StringBuilder();
                for (Integer photo : nums) {
                    pointsStr.append(photo).append(", ");
                }
                binding.warning.setText(
                        String.format("?? ?????????????? ?????????????????????? ??????????: %s c???????? ???????????? ???????????????????? ?????? " +
                                        "???? ??????????. ?????????????????? ???????????? ???? ?? ????????, ?????? ???????????????? ?????????????? " +
                                        "???? ?????????????????????? ??????????????????",
                                pointsStr));
            } else {
                binding.warning.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.photos_menu, menu);
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sync) {
            uploadPhotos(true);
        }
        if (item.getItemId() == R.id.actionAddFromCamera) {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
        }
        if (item.getItemId() == R.id.actionAddFromGallery) {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            intent.putExtra("fromGallery", true);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    // on update
    @Override
    public void onResume() {
        super.onResume();
        teamId = getContext().getSharedPreferences("team", Context.MODE_PRIVATE).
                getInt("team_id", 0);
    }

    public void uploadPhotos(boolean withToast) {
        AsyncTask.execute(() -> {
            uploadLocalPhotos(withToast);
            uploadInternetPhotos(withToast);
        });
    }

    public void uploadLocalPhotos(boolean withToast) {
        boolean localResult = true;
        List<Photo> notLocalSync = mPhotoViewModel.getNotLocalSyncPhoto(teamId);
        for (Photo photo : notLocalSync) {
            boolean isSuccess = upload_photo(photo, "http://192.168.88.164/api/v1/upload_photo");
            if (isSuccess) {
                photo.setSyncLocal(true);
                mPhotoViewModel.update(photo);
            } else {
                localResult = false;
            }
        }
        if (withToast) {
            if (localResult) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "???????? ???????????????? ????????????????????", Toast.LENGTH_SHORT).show();
                });
            } else {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "???????????? ?????? ?????????????????? ???????????????? ????????", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    public void uploadInternetPhotos(boolean withToast) {
        boolean internetResult = true;
        List<Photo> notSync = mPhotoViewModel.getNotSyncPhoto(teamId);
        for (Photo photo : notSync) {
            boolean isSuccess = upload_photo(photo, "https://kolco24.ru/api/v1/upload_photo");
            if (isSuccess) {
                photo.setSync(true);
                mPhotoViewModel.update(photo);
            } else {
                internetResult = false;
            }
        }
        if (withToast) {
            if (internetResult) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "???????? ???????????????????? ?????????? ????????????????", Toast.LENGTH_SHORT).show();
                });
            } else {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "???????????? ?????? ???????????????? ?????????? ????????????????", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    public boolean upload_photo(Photo photo, String url) {
        File file = new File(photo.photo_url);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("team_id", String.valueOf(teamId))
                .addFormDataPart("point_number", String.valueOf(photo.getPointNumber()))
                .addFormDataPart("photo", file.getName(), RequestBody.create(file, MediaType.parse("image/*")))
                .build();
        Request request = new Request.Builder().url(url).post(requestBody).build();
        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (Exception e) {
            return false;
        }
        if (response.isSuccessful()) {
            try {
                JSONObject jsonObject = new JSONObject(response.body().string());
                if (jsonObject.getBoolean("success")) {
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
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