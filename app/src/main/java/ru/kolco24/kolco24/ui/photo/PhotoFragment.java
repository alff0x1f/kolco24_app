package ru.kolco24.kolco24.ui.photo;


import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.entities.Photo;
import ru.kolco24.kolco24.databinding.FragmentPhotosBinding;

public class PhotoFragment extends Fragment implements MenuProvider {
    private final int LOCAL_SYNC = 1;
    private final int INTERNET_SYNC = 2;
    public final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    private FragmentPhotosBinding binding;
    private PhotoViewModel mPhotoViewModel;
    private final PhotoPointListAdapter adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
    private int teamId;
    private String phoneUuid;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentPhotosBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        //
        teamId = requireContext().getSharedPreferences(
                "team",
                Context.MODE_PRIVATE
        ).getInt("team_id", 0);

        phoneUuid = requireContext().getSharedPreferences(
                "team",
                Context.MODE_PRIVATE
        ).getString("phone_uuid", "");

        if (phoneUuid.isEmpty()) {
            phoneUuid = java.util.UUID.randomUUID().toString();
            requireContext().getSharedPreferences(
                    "team",
                    Context.MODE_PRIVATE
            ).edit().putString("phone_uuid", phoneUuid).apply();
        }

        // recycle view
        RecyclerView recyclerView = binding.myPointsRecyclerView;
//        adapter = new PhotoPointListAdapter(new PhotoPointListAdapter.PhotoPointDiff());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(3, 8, false));
        // Get a new or existing ViewModel from the ViewModelProvider.
        mPhotoViewModel = new ViewModelProvider(this).get(PhotoViewModel.class);
        new LoadPhotosAsyncTask().execute(String.valueOf(teamId));
        //fab
        FloatingActionButton fab = binding.fab;
        fab.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
//            NavHostFragment.findNavController(this).navigate(R.id.action_navigation_taken_points_to_navigation_new_photo);
        });

        // Add the MenuProvider to handle menu creation
        requireActivity().addMenuProvider(this, getViewLifecycleOwner());

        return root;
    }

    private class LoadPhotosAsyncTask extends AsyncTask<String, Void, List<Photo>> {
        @Override
        protected List<Photo> doInBackground(String... params) {
            int teamId = Integer.parseInt(params[0]);
            return mPhotoViewModel.getPhotos(teamId);
        }

        @Override
        protected void onPostExecute(List<Photo> photos) {
            super.onPostExecute(photos);
            adapter.submitList(photos);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPhotoViewModel.getPhotoCount(teamId).observe(
                getViewLifecycleOwner(),
                count -> binding.textDashboard.setText(String.format("Количество КП: %d", count))
        );
        mPhotoViewModel.getCostSum(teamId).observe(getViewLifecycleOwner(), sum -> {
            if (sum == null) {
                binding.textDashboard2.setText("Сумма баллов: 0");
            } else {
                binding.textDashboard2.setText(String.format("Сумма баллов: %d", sum));
            }
        });
        mPhotoViewModel.getTeam(teamId).observe(getViewLifecycleOwner(), team -> {
            if (team != null) {
                binding.teamName.setText(
                        String.format("%s: %s", team.getStartNumber(), team.getTeamname()));
            }
        });
        mPhotoViewModel.getNonLegendPointNumbers(teamId).observe(getViewLifecycleOwner(), nums -> {
            if (nums != null && nums.size() > 0) {
                binding.warning.setVisibility(View.VISIBLE);
                StringBuilder pointsStr = new StringBuilder();
                for (Integer photo : nums) {
                    pointsStr.append(photo).append(", ");
                }
                binding.warning.setText(
                        String.format("В легенде отсутствуют номер: %s cумма баллов подсчитана без " +
                                        "их учета. До старта соревнований это нормально, если " +
                                        "брифинг уже прошел, то обновите легенду до актуального состояния",
                                pointsStr));
            } else {
                binding.warning.setVisibility(View.GONE);
            }
        });
        if (teamId == 0) {
            binding.resultHeader.setVisibility(View.GONE);
            binding.textDashboard.setVisibility(View.GONE);
            binding.textDashboard2.setVisibility(View.GONE);
            binding.myPointsRecyclerView.setVisibility(View.GONE);
            binding.hr.setVisibility(View.GONE);
            binding.hr2.setVisibility(View.GONE);
            binding.warning.setVisibility(View.GONE);
            binding.textNoTeamId.setVisibility(View.VISIBLE);
        } else {
            binding.resultHeader.setVisibility(View.VISIBLE);
            binding.textDashboard.setVisibility(View.VISIBLE);
            binding.textDashboard2.setVisibility(View.VISIBLE);
            binding.myPointsRecyclerView.setVisibility(View.VISIBLE);
            binding.hr.setVisibility(View.VISIBLE);
            binding.hr2.setVisibility(View.VISIBLE);
            binding.textNoTeamId.setVisibility(View.GONE);
        }
    }

    // on update
    @Override
    public void onResume() {
        super.onResume();
        teamId = requireContext().getSharedPreferences("team", Context.MODE_PRIVATE).
                getInt("team_id", 0);
        new LoadPhotosAsyncTask().execute(String.valueOf(teamId));
    }

    public void uploadPhotos(boolean withToast) {
        AsyncTask.execute(() -> {
//            uploadLocalPhotos(withToast);
            uploadInternetPhotos(withToast);
        });
    }

    public void uploadLocalPhotos(boolean withToast) {
        boolean localResult = true;
        List<Photo> notLocalSync = mPhotoViewModel.getNotLocalSyncPhoto(teamId);
        for (Photo photo : notLocalSync) {
            boolean isSuccess = upload_photo(photo, "http://192.168.1.5/api/v1/upload_photo");
            if (isSuccess) {
                photo.setSyncLocal(true);
                mPhotoViewModel.update(photo);
            } else {
                localResult = false;
                break;
            }
        }
        if (withToast) {
            if (localResult) {
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Фото локально отправлены", Toast.LENGTH_SHORT).show());
            } else {
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка при локальной отправке фото", Toast.LENGTH_SHORT).show());
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
                break;
            }
        }
        List<Photo> photos = mPhotoViewModel.getPhotos(teamId);
        requireActivity().runOnUiThread(
                () -> adapter.submitList(photos)
        );
        if (withToast) {
            if (internetResult) {
                requireActivity().runOnUiThread(
                        () -> Toast.makeText(
                                getContext(),
                                "Фото отправлены через интернет",
                                Toast.LENGTH_SHORT).show()
                );
            } else {
                requireActivity().runOnUiThread(
                        () -> Toast.makeText(
                                getContext(),
                                "Ошибка при отправке через интернет",
                                Toast.LENGTH_SHORT).show()
                );
            }
        }
    }

    public boolean upload_photo(@NonNull Photo photo, String url) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("team_id", String.valueOf(teamId))
                .addFormDataPart("point_number", String.valueOf(photo.getPointNumber()))
                .addFormDataPart("timestamp", String.valueOf(photo.getTime()))
                .addFormDataPart("nfc", photo.getPointNfc())
                .addFormDataPart("phone_uuid", phoneUuid);

        if (!photo.getPhotoUrl().isEmpty()) {
            File file = new File(photo.getPhotoUrl());
            builder.addFormDataPart("photo", file.getName(), RequestBody.create(file, MediaType.parse("image/*")));
        }
        RequestBody requestBody = builder.build();

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

    // MenuProvider interface
    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menu.clear(); // Clear the menu before inflating it
        menuInflater.inflate(R.menu.photos_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.action_sync) {
            uploadPhotos(true);
        }
        if (menuItem.getItemId() == R.id.actionAddFromCamera) {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            startActivity(intent);
        }
        if (menuItem.getItemId() == R.id.actionAddFromGallery) {
            Intent intent = new Intent(getActivity(), NewPhotoActivity.class);
            intent.putExtra("fromGallery", true);
            startActivity(intent);
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