package ru.kolco24.kolco24.sync

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.SettingsPreferences
import ru.kolco24.kolco24.data.entities.Photo
import java.io.File
import java.util.concurrent.TimeUnit

enum class UploadTarget { AUTO, LOCAL, REMOTE }
class PhotoUploader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun buildUploadUrl(useLocal: Boolean, raceId: Int): String {
        val API_BASE_URL = "https://kolco24.ru/api/"
        val API_LOCAL_BASE_URL = "http://192.168.1.5/api/"
        val base = if (useLocal) API_LOCAL_BASE_URL else API_BASE_URL
        return "$base" + "race/$raceId/upload_photo/"
    }

    private fun uploadOne(photo: Photo, url: String, teamId: Int, phoneUuid: String): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("team_id", teamId.toString())
            .addFormDataPart("point_number", photo.pointNumber.toString())
            .addFormDataPart("timestamp", photo.time.toString())
            .addFormDataPart("nfc", photo.pointNfc)
            .addFormDataPart("phone_uuid", phoneUuid)

        if (photo.photoUrl.isNotEmpty()) {
            val f = File(photo.photoUrl)
            builder.addFormDataPart(
                "photo",
                f.name,
                RequestBody.create("image/*".toMediaTypeOrNull(), f)
            )
        }

        val req = Request.Builder().url(url).post(builder.build()).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val ok = JSONObject(resp.body?.string() ?: "{}").optBoolean("success", false)
            return ok
        }
    }

    /**
     * Возвращает true, если всё ушло без ошибок. Частичный успех допустим, но метод вернёт false.
     */
    fun uploadPending(target: UploadTarget = UploadTarget.AUTO): Boolean {
        val db = AppDatabase.getDatabase(context)
        val teamId = SettingsPreferences.getSelectedTeamId(context)
        if (teamId == 0) return true // нечего делать

        val useLocal = when (target) {
            UploadTarget.AUTO   -> SettingsPreferences.shouldUseLocalServer(context)
            UploadTarget.LOCAL  -> true
            UploadTarget.REMOTE -> false
        }

        val raceId = SettingsPreferences.getRaceId(context)
        val url = buildUploadUrl(useLocal, raceId)
        val phoneUuid = SettingsPreferences.ensurePhoneUuid(context)

        val toUpload: List<Photo> = if (useLocal)
            db.photoDao().getNotLocalSyncPhoto(teamId)
        else
            db.photoDao().getNotSyncPhoto(teamId)

        var allOk = true
        for (p in toUpload) {
            val ok = runCatching { uploadOne(p, url, teamId, phoneUuid) }.getOrDefault(false)
            if (ok) {
                if (useLocal) p.isSyncLocal = true else p.isSync = true
                db.photoDao().update(p)
            } else {
                allOk = false
                // не прерываем цикл: отправим остальное, а WorkManager сам сделает retry по backoff
            }
        }
        return allOk
    }
}
