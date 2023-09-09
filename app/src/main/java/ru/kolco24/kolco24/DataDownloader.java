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
import ru.kolco24.kolco24.data.Point;
import ru.kolco24.kolco24.data.PointDao;
import ru.kolco24.kolco24.data.Team;
import ru.kolco24.kolco24.data.TeamDao;

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
                .url("https://kolco24.ru/api/v1/points")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                System.out.println("Failure");
                showToast("Failure");
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

    public void downloadTeams(String categoryCode) {
        final String url = "https://kolco24.ru/api/v1/teams";
        HttpUrl httpUrl = HttpUrl.parse(url);

        if (httpUrl == null) {
            showToast("Неверный URL");
            return;
        }
        HttpUrl.Builder httpBuilder = httpUrl.newBuilder();

        if (categoryCode != null) {
            httpBuilder.addQueryParameter("category", categoryCode);
        }
        Request request = new Request.Builder().url(httpBuilder.build()).build();

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
                            int team_id = team.getInt("id");
                            Team exist_team = mTeamDao.getTeamById(team_id);

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
                                mTeamDao.insert(exist_team);
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
                                mTeamDao.update(exist_team);
                            }
                        }
                        showToast("Список команд обновлен");
                        executeCallback();
                    } catch (JSONException e) {
                        e.printStackTrace();
                        showToast("Ошибка декодирования JSON");
                    }
                }
            }
        });
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