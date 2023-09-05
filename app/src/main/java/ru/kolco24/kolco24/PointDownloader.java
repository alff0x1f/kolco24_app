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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.Point;
import ru.kolco24.kolco24.data.PointDao;

public class PointDownloader {
    final private PointDao mPointDao;
    final private Context mContext;
    DownloadCallback mCallback;
    private boolean showToasts = true;

    public interface DownloadCallback {
        void onDownloadComplete();
    }

    public PointDownloader(Application application, DownloadCallback callback) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPointDao = db.pointDao();
        mContext = application.getApplicationContext();
        mCallback = callback;
    }

    public PointDownloader(Application application) {
        this(application, null);
    }

    public void downloadPoints() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();

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