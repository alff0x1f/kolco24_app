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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.dao.PointTagDao;
import ru.kolco24.kolco24.data.entities.Checkpoint;
import ru.kolco24.kolco24.data.daos.PointDao;
import ru.kolco24.kolco24.data.entities.PointTag;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.data.daos.TeamDao;

public class DataDownloader {
    final private PointDao mPointDao;
    final private PointTagDao pointTagDao;
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
    private static final String TAGS_ENDPOINT = "race/1/point_tags";

    public interface DownloadCallback {
        void onDownloadComplete();
    }

    public DataDownloader(Application application, DownloadCallback callback) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPointDao = db.pointDao();
        mTeamDao = db.teamDao();
        pointTagDao = db.pointTagDao();
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
                    String legend = responseBody.string();

                    try {
                        JSONArray jObj = new JSONArray(legend);
                        boolean isUpdated = false;
                        for (int i = 0; i < jObj.length(); i++) {
                            JSONObject point = jObj.getJSONObject(i);

                            Checkpoint newPoint = Checkpoint.fromJson(point);
                            if (updateOrInsertPoint(newPoint)) {
                                isUpdated = true;
                                // если точка обновлена, то обновляем теги
                                JSONArray tags = point.getJSONArray("tags");
                                for (int j = 0; j < tags.length(); j++) {
                                    String tagId = tags.getString(j);

                                    PointTag existPointTag = pointTagDao.getPointTagByTag(tagId);
                                    if (existPointTag == null) {
                                        // create new point
                                        PointTag pointTag = new PointTag(newPoint.getId(), tagId);
                                        pointTagDao.insertPointTag(pointTag);
                                    }
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
     * Добавляет тег на сайт
     *
     * @param PointId - id точки
     * @param PointNumber - номер точки
     */
    public void uploadTag(String PointId, int PointNumber){
        // Create a JSON object to send to the server
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("tag_id", PointId);
            jsonObject.put("point_number", PointNumber);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }


        // Create a request body with JSON content
        RequestBody requestBody = RequestBody.create(
                jsonObject.toString(),
                MediaType.parse("application/json")
        );

        // Build the POST request with the JSON body
        Request request = new Request.Builder()
                .url(getBaseUrl() + TAGS_ENDPOINT) // Replace UPLOAD_ENDPOINT with your server endpoint
                .post(requestBody)
                .build();

        // Execute the request asynchronously
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showToast("Сервер недоступен");
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    // Handle successful response from the server
                    showToast("Объект успешно загружен");
                } else {
                    System.out.println(response.body().toString());
                    // Handle unsuccessful response
                    showToast("Ошибка " + response.code());
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
            try {
                mTeamDao.insert(team);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

        if (!existTeam.equals(team)) {
            // update team
            existTeam.setPaidPeople(team.getPaidPeople());
            existTeam.setDist(team.getDist());
            existTeam.setCategory(team.getCategory());
            existTeam.setTeamname(team.getTeamname());
            existTeam.setCity(team.getCity());
            existTeam.setOrganization(team.getOrganization());
            existTeam.setYear(team.getYear());
            existTeam.setStartNumber(team.getStartNumber());
            existTeam.setPlace(team.getPlace());
            existTeam.setStartTime(team.getStartTime());
            existTeam.setFinishTime(team.getFinishTime());
            existTeam.setDnf(team.isDnf());

            mTeamDao.update(existTeam);
            return true;
        }

        return false;
    }

    private boolean updateOrInsertPoint(Checkpoint point) {
        Checkpoint existPoint = mPointDao.getPointById(point.getId());
        if (existPoint == null) {
            // create new point
            try {
                mPointDao.insert(point);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

        // update point
        boolean isUpdated = false;
        if (existPoint.getNumber() != point.getNumber()) {
            existPoint.setNumber(point.getNumber());
            isUpdated = true;
        }

        if (!existPoint.getDescription().equals(point.getDescription())) {
            existPoint.setDescription(point.getDescription());
            isUpdated = true;
        }

        if (existPoint.getCost() != point.getCost()) {
            existPoint.setCost(point.getCost());
            isUpdated = true;
        }
        if (isUpdated) {
            mPointDao.update(existPoint);
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