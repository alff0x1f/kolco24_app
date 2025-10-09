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
import ru.kolco24.kolco24.data.daos.CheckpointDao;
import ru.kolco24.kolco24.data.daos.TeamDao;
import ru.kolco24.kolco24.data.SettingsPreferences;
import ru.kolco24.kolco24.data.entities.Checkpoint;
import ru.kolco24.kolco24.data.entities.CheckpointTag;
import ru.kolco24.kolco24.data.entities.MemberTag;
import ru.kolco24.kolco24.data.entities.Team;


public class DataDownloader {
    final private AppDatabase db;
    final private CheckpointDao mCheckpointDao;
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

    private static final String API_BASE_URL = "https://kolco24.ru/api/";
    private static final String API_LOCAL_BASE_URL = "http://192.168.1.5/api/";
    private boolean isLocalDownload;
    private static final String TEAMS_SUFFIX = "teams/";
    private static final String CHECKPOINT_SUFFIX = "checkpoint/";
    private static final String TAGS_SUFFIX = "point_tags/";
    private static final String MEMBER_TAG_ENDPOINT = "member_tag/";

    public interface DownloadCallback {
        void onDownloadComplete();
    }

    public DataDownloader(Application application, DownloadCallback callback) {
        db = AppDatabase.getDatabase(application);
        mCheckpointDao = db.checkpointDao();
        mTeamDao = db.teamDao();
        pointTagDao = db.pointTagDao();
        mContext = application.getApplicationContext();
        mCallback = callback;
        isLocalDownload = SettingsPreferences.shouldUseLocalServer(mContext);
    }

    public DataDownloader(Application application) {
        this(application, null);
    }

    public void downloadCheckpoints() {
        Request request = new Request.Builder()
                .url(getBaseUrl() + buildRaceEndpoint(CHECKPOINT_SUFFIX))
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
                            JSONObject checkpoint = jObj.getJSONObject(i);

                            Checkpoint newPoint = Checkpoint.fromJson(checkpoint);
                            if (updateOrInsertPoint(newPoint)) {
                                isUpdated = true;
                            }
                            // update checkpoint tags
                            JSONArray tags = checkpoint.getJSONArray("tags");
                            for (int j = 0; j < tags.length(); j++) {
                                JSONObject tagObject = tags.getJSONObject(j);
                                String tagUID = tagObject.getString("tag_id");
                                int id = tagObject.getInt("id");
                                String checkMethod = tagObject.getString("check_method");

                                CheckpointTag existCheckpointTag = pointTagDao.getPointTagByUID(tagUID);
                                if (existCheckpointTag == null) {
                                    // Create new tag with additional fields
                                    CheckpointTag checkpointTag = new CheckpointTag(
                                            id,
                                            newPoint.getId(),
                                            tagUID, // tagUID
                                            checkMethod
                                    );
                                    pointTagDao.insertPointTag(checkpointTag);
                                } else {
                                    // Optionally update existing tag if necessary
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
                        e.printStackTrace();
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

    private int getCurrentRaceId() {
        return SettingsPreferences.getRaceId(mContext);
    }

    private String buildRaceEndpoint(String suffix) {
        return "race/" + getCurrentRaceId() + "/" + suffix;
    }

    private HttpUrl buildTeamsUrl(Integer categoryCode) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(getBaseUrl() + buildRaceEndpoint(TEAMS_SUFFIX)).newBuilder();

        if (categoryCode != null) {
            urlBuilder.addQueryParameter("category", categoryCode.toString());
        }

        return urlBuilder.build();
    }

    public void downloadTeams(Integer categoryCode) {
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

    public void downloadMemberTags() {
        Request request = new Request.Builder()
                .url(getBaseUrl() + MEMBER_TAG_ENDPOINT)
                .build();

        System.out.println("request: " + request.toString());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showToast("Ошибка обновления меток участников, нет связи с сервером");
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

                    // Parse and insert MemberTags into the database
                    String memberTagsJson = responseBody.string();
                    try {
                        JSONArray jArray = new JSONArray(memberTagsJson);
                        System.out.println("memberTags: " + jArray.toString());
                        boolean isUpdated = false;
                        for (int i = 0; i < jArray.length(); i++) {
                            JSONObject memberTagObj = jArray.getJSONObject(i);
                            System.out.println("memberTag " + i + ": " + memberTagObj.toString());
                            MemberTag newMemberTag = MemberTag.fromJson(memberTagObj);
                            if (updateOrInsertMemberTag(newMemberTag)) {
                                isUpdated = true;
                            }
                        }
                        if (isUpdated) {
                            showToast("Метки участников обновлены");
                        } else {
                            showToast("Нет новых меток участников");
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
     * @param PointId     - id точки
     * @param PointNumber - номер точки
     */
    public void uploadTag(String PointId, int PointNumber) {
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
                .url(getBaseUrl() + buildRaceEndpoint(TAGS_SUFFIX)) // Replace UPLOAD_ENDPOINT with your server endpoint
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
                    System.out.println(response.body() != null ? response.body().string() : "Response body is null");
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
            existTeam.setUcount(team.getUcount());
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

    public boolean updateOrInsertMemberTag(MemberTag newMemberTag) {
        // Logic to check if MemberTag exists in the database
        MemberTag existingMemberTag = db.memberTagDao().getMemberTagById(newMemberTag.getId());

        if (existingMemberTag == null) {
            // Update the existing MemberTag
            db.memberTagDao().insertMemberTag(newMemberTag);
            return true; // Indicating the database was updated
        } else if (existingMemberTag.getNumber() != newMemberTag.getNumber() && existingMemberTag.getTagId() != newMemberTag.getTagId()) {
            // Insert new MemberTag
            db.memberTagDao().updateMemberTag(newMemberTag);
            return true; // Indicating new entry was added
        }
        return false; // Indicating no changes were made
    }

    private boolean updateOrInsertPoint(Checkpoint point) {
        Checkpoint existPoint = mCheckpointDao.getCheckpointById(point.getId());
        if (existPoint == null) {
            // create new point
            try {
                mCheckpointDao.insert(point);
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

        if (!existPoint.getType().equals(point.getType())) {
            existPoint.setType(point.getType());
            isUpdated = true;
        }
        if (isUpdated) {
            mCheckpointDao.update(existPoint);
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
