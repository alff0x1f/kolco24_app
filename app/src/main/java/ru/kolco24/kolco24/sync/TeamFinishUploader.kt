package ru.kolco24.kolco24.sync

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.SettingsPreferences
import ru.kolco24.kolco24.data.entities.TeamFinish
import java.util.concurrent.TimeUnit

class TeamFinishUploader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(useLocal: Boolean, raceId: Int): String {
        val API_BASE_URL = "https://kolco24.ru/api/"
        val API_LOCAL_BASE_URL = "http://192.168.1.5/api/"
        val base = if (useLocal) API_LOCAL_BASE_URL else API_BASE_URL
        return base + "race/$raceId/team_finish/"
    }

    private fun buildPayload(event: TeamFinish): JSONObject {
        return JSONObject().apply {
            put("member_tag_id", event.memberTagId)
            put("tag_uid", event.tagUid)
            put("recorded_at", event.recordedAt)
        }
    }

    fun uploadPending(useLocal: Boolean): Boolean {
        val db = AppDatabase.getDatabase(context)
        val pending = if (useLocal) db.teamFinishDao().getPendingLocal() else db.teamFinishDao().getPendingRemote()
        if (pending.isEmpty()) return true

        val raceId = SettingsPreferences.getRaceId(context)
        val url = buildUrl(useLocal, raceId)
        var allOk = true
        for (event in pending) {
            val body = RequestBody.create("application/json".toMediaTypeOrNull(), buildPayload(event).toString())
            val request = Request.Builder().url(url).post(body).build()
            val success = runCatching {
                client.newCall(request).execute().use { resp ->
                    resp.isSuccessful && JSONObject(resp.body?.string() ?: "{}").optBoolean("success", false)
                }
            }.getOrDefault(false)
            if (success) {
                if (useLocal) db.teamFinishDao().markLocalSynced(event.id, true)
                else db.teamFinishDao().markRemoteSynced(event.id, true)
            } else {
                allOk = false
            }
        }
        return allOk
    }
}
