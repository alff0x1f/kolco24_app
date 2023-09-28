package ru.kolco24.kolco24;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.entities.Point;
import ru.kolco24.kolco24.data.daos.PointDao;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.data.daos.TeamDao;

public class DataDownloader {
    final private PointDao mPointDao;
    final private TeamDao mTeamDao;
    final private Context mContext;
    final private DownloadCallback mCallback;
    private boolean showToasts = true;
    final private OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    private static final String API_BASE_URL = "https://kolco24.ru/api/v1/";
    private static final String API_LOCAL_BASE_URL = "http://192.168.1.5/api/v1/";
    private boolean isLocalDownload = false;
    private static final String TEAMS_ENDPOINT = "teams";
    private static final String POINTS_ENDPOINT = "points";

    public interface DownloadCallback {
        void onDownloadComplete();
    }

    public DataDownloader(Application application, DownloadCallback callback) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPointDao = db.pointDao();
        mTeamDao = db.teamDao();
        mContext = application.getApplicationContext();
        mCallback = callback;
    }

    public DataDownloader(Application application) {
        this(application, null);
    }

    public void downloadPoints() {
        Request request = new Request.Builder()
                .url(getBaseUrl() + POINTS_ENDPOINT)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showToast("Сервер недоступен");
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        showToast("Ошибка " + response.code());
                        executeCallback();
                        throw new IOException("Unexpected code " + response);
                    }

                    if (responseBody == null) {
                        showToast("Пустой ответ");
                        executeCallback();
                        throw new IOException("Empty response");
                    }

                    // insert in DB
                    String legend = responseBody.string();

                    try {
                        JSONArray jObj = new JSONArray(legend);
                        boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject point = jObj.getJSONObject(i);

                            int number = point.getInt("number");
                            String description = point.getString("description");
                            int cost = point.getInt("cost");

                            Point existPoint = mPointDao.getPointByNumber(number);

                            if (existPoint == null) {
                                mPointDao.insert(new Point(
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
                                    mPointDao.update(existPoint);
                                    isUpdated = true;
                                }
                            }
                        }
                        if (isUpdated) {
                            showToast("Список обновлен");
                        } else {
                            showToast("Нет новых КП");
                        }
                    } catch (JSONException e) {
                        showToast("Ошибка декодирования JSON");
                        executeCallback();
                        throw new IOException("Wrong JSON");
                    }
                }

                executeCallback();
            }
        });
    }

    public void setLocalDownload(boolean isLocal) {
        this.isLocalDownload = isLocal;
    }

    private String getBaseUrl() {
        if (isLocalDownload) {
            return API_LOCAL_BASE_URL;
        }
        return API_BASE_URL;
    }

    private HttpUrl buildTeamsUrl(String categoryCode) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(getBaseUrl() + TEAMS_ENDPOINT).newBuilder();

        if (categoryCode != null) {
            urlBuilder.addQueryParameter("category", categoryCode);
        }

        return urlBuilder.build();
    }

    public void downloadTeams(String categoryCode) {
        Request request = new Request.Builder().url(buildTeamsUrl(categoryCode)).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showToast("Ошибка обновления списка, нет связи с сервером");
                executeCallback();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        showToast("Ошибка " + response.code());
                        executeCallback();
                        throw new IOException("Unexpected code " + response);
                    }

                    if (responseBody == null) {
                        showToast("Пустой ответ");
                        executeCallback();
                        throw new IOException("Empty response");
                    }

                    // insert in DB
                    String teams = responseBody.string();
                    try {
                        JSONArray jObj = new JSONArray(teams);
                        boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject team = jObj.getJSONObject(i);
                            Team newTeam = Team.fromJson(team);
                            if (updateOrInsertTeam(newTeam)) {
                                isUpdated = true;
                            }
                        }
                        if (isUpdated) {
                            showToast("Список команд обновлен");
                        } else {
                            showToast("Нет новых команд");
                        }
                        executeCallback();
                    } catch (JSONException e) {
                        e.printStackTrace();
                        showToast("Ошибка декодирования JSON");
                        executeCallback();
                    }
                }
            }
        });
    }

    /**
     * Обновляет или добавляет команду в БД
     *
     * @param team - команда
     * @return true, если команда обновлена или добавлена, иначе false
     */
    private boolean updateOrInsertTeam(Team team) {
        Team existTeam = mTeamDao.getTeamById(team.getId());
        if (existTeam == null) {
            // create new team
            mTeamDao.insert(team);
            return true;
        }

        if (!existTeam.equals(team)) {
            // update team
            existTeam.paid_people = team.paid_people;
            existTeam.dist = team.dist;
            existTeam.category = team.category;
            existTeam.teamname = team.teamname;
            existTeam.city = team.city;
            existTeam.organization = team.organization;
            existTeam.year = team.year;
            existTeam.start_number = team.start_number;

            mTeamDao.update(existTeam);
            return true;
        }

        return false;
    }


    private void executeCallback() {
        if (mCallback != null) {
            mCallback.onDownloadComplete();
        }
    }

    public void hideToasts() {
        showToasts = false;
    }

    private void showToast(final String message) {
        if (showToasts) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show());
        }
    }
}