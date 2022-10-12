package ru.kolco24.kolco24.ui.teams;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.Team;
import ru.kolco24.kolco24.databinding.FragmentTeamsBinding;

public class TeamsFragment extends Fragment {
    private FragmentTeamsBinding binding;
    private TeamViewModel mTeamViewModel;
    private SharedPreferences sharedpreferences;

    private final OkHttpClient client = new OkHttpClient();

    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        TeamsViewModel teamsViewModel =
                new ViewModelProvider(this).get(TeamsViewModel.class);

        mTeamViewModel = new ViewModelProvider(this).get(TeamViewModel.class);

        binding = FragmentTeamsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        //pager
        ViewPager2 viewPager = binding.viewPagerTeams;
        viewPager.setAdapter(new CategoriesAdapter(this));

//        final TextView textView = binding.textHome;
//        teamsViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        sharedpreferences = getActivity().getSharedPreferences("team", Context.MODE_PRIVATE);
        String team = sharedpreferences.getString("team", "");

        // QR code
        binding.fabQr.setOnClickListener(this::onClick);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        attachTabToViewPager(binding.tabTeams, binding.viewPagerTeams);
    }

    private void attachTabToViewPager(TabLayout tabLayout, ViewPager2 viewPager) {
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(CategoriesAdapter.getCategoryName(position))
        ).attach();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode == RESULT_OK) {
            String contents = data.getStringExtra("SCAN_RESULT");
            String[] qr_content = contents.split(":");
            if (qr_content.length != 3 || !qr_content[0].equals("t") || !qr_content[1].equals("2022")) {
                Toast.makeText(getActivity(), "Неверный QR код", Toast.LENGTH_LONG).show();
                return;
            }
            String team = qr_content[2];
            int team_number = Integer.parseInt(team);

            Toast toast = Toast.makeText(
                    getContext(),
                    String.format("Команда %d", team_number),
                    Toast.LENGTH_LONG
            );
            toast.show();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.team_menu, menu);
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_scan_qr) {
            onClick(this.getView());
        }
        if (item.getItemId() == R.id.action_update) {
            downloadTeams();
        }
        return super.onOptionsItemSelected(item);
    }

    public void downloadTeams() {
        Request request = new Request.Builder()
                .url("http://192.168.88.148:8000/api/v1/teams")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.out.println("Failure");
                e.printStackTrace();
                toast("Ошибка обновления списка, нет связи с сервером");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        toast("Ошибка " + response.code());
                        throw new IOException("Unexpected code " + response);
                    }
                    // insert in DB
                    String teams = responseBody.string();
                    try {
                        JSONArray jObj = new JSONArray(teams);
                        Boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject team = jObj.getJSONObject(i);
                            int team_id = team.getInt("id");
                            System.out.println(team_id);
                            Team exist_team = mTeamViewModel.getTeamById(team_id);

                            if (exist_team == null) {
                                exist_team = new Team(
                                        team_id,
                                        "",
                                        (float) team.getDouble("paid_people"),
                                        team.getString("dist"),
                                        team.getString("category"),
                                        team.getString("teamname"),
                                        team.getString("city"),
                                        team.getString("organization"),
                                        Integer.toString(team.getInt("year")),
                                        team.getString("start_number"),
                                        0L,
                                        0L,
                                        false,
                                        0
                                );
                                mTeamViewModel.insert(exist_team);
                                isUpdated = true;
                            } else {
                                exist_team.setPaid_people((float) team.getDouble("paid_people"));
                                exist_team.setDist(team.getString("dist"));
                                exist_team.setCategory(team.getString("category"));
                                exist_team.setTeamname(team.getString("teamname"));
                                exist_team.setCity(team.getString("city"));
                                exist_team.setOrganization(team.getString("organization"));
                                exist_team.setYear(Integer.toString(team.getInt("year")));
                                exist_team.setStart_number(team.getString("start_number"));
                                mTeamViewModel.update(exist_team);
                                System.out.println("updated " + team_id);
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        toast("Ошибка декодирования JSON");
                    }

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
        });
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void onClick(View view) {
        try {
            Intent intent = new Intent("com.google.zxing.client.android.SCAN");
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE"); // "PRODUCT_MODE for bar codes
            startActivityForResult(intent, 0);

        } catch (Exception e) {
            Uri marketUri = Uri.parse("market://details?id=com.srowen.bs.android");
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, marketUri);
            startActivity(marketIntent);
        }
    }
}