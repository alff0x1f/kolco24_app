package ru.kolco24.kolco24.ui.teams;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.data.Team;
import ru.kolco24.kolco24.data.TeamRepository;


public class TeamViewModel extends AndroidViewModel {
    private TeamRepository mRepository;
    private final LiveData<List<Team>> mAllTeams;
    private final OkHttpClient client = new OkHttpClient();
    private MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private MutableLiveData<Boolean> isLoading = new MutableLiveData<>();

    /*__init__*/
    public TeamViewModel(Application application) {
        super(application);
        mRepository = new TeamRepository(application);
        mAllTeams = mRepository.getAllTeams();
        toastMessage.setValue(null);
        isLoading.setValue(false);
    }

    LiveData<List<Team>> getAllTeams() {
        return mAllTeams;
    }

    LiveData<List<Team>> getTeamsByCategory(String category) {
        return mRepository.getTeamsByCategory(category);
    }

    public Team getTeamById(int id) {
        return mRepository.getTeamById(id);
    }

    public void insert(Team team) {
        mRepository.insert(team);
    }

    public void update(Team team) {
        mRepository.update(team);
    }

    public void downloadTeams(String url) {
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                toastMessage.postValue("Ошибка обновления списка, нет связи с сервером");
                isLoading.postValue(false);
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        toastMessage.postValue("Ошибка " + response.code());
                        isLoading.postValue(false);
                        throw new IOException("Unexpected code " + response);
                    }
                    // insert in DB
                    String teams = responseBody.string();
                    try {
                        JSONArray jObj = new JSONArray(teams);
                        boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject team = jObj.getJSONObject(i);
                            int team_id = team.getInt("id");
                            System.out.println(team_id);
                            Team exist_team = getTeamById(team_id);

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
                                insert(exist_team);
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
                                update(exist_team);
                            }
                        }
                        toastMessage.postValue("Список команд обновлен");
                        isLoading.postValue(false);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        toastMessage.postValue("Ошибка декодирования JSON");
                    }

                }
            }
        });
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public void postToastMessage(String message) {
        toastMessage.postValue(message);
    }

    public void clearToastMessage() {
        toastMessage.postValue(null);
    }

    public LiveData<Boolean> isLoading() {
        return isLoading;
    }
}
