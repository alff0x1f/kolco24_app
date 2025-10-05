package ru.kolco24.kolco24.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SettingsPreferences {
    private const val PREF_NAME = "settings"
    private const val TEAM_PREF_NAME = "team"

    const val KEY_RACE_ID = "current_race_id"
    const val KEY_CATEGORY_ID = "current_category_id"
    const val KEY_TEAM_ID = "team_id"
    const val KEY_TEAM_NAME = "team_name"
    const val KEY_TEAM_NUMBER = "team_number"

    const val DEFAULT_RACE_ID = 8
    const val DEFAULT_CATEGORY_CODE = 8

    @JvmStatic
    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getRaceId(context: Context): Int =
        getPrefs(context).getInt(KEY_RACE_ID, DEFAULT_RACE_ID)

    @JvmStatic
    fun setRaceId(context: Context, raceId: Int) {
        getPrefs(context).edit { putInt(KEY_RACE_ID, raceId) }
    }

    @JvmStatic
    fun getSelectedCategory(context: Context): Int =
        getPrefs(context).getInt(KEY_CATEGORY_ID, DEFAULT_CATEGORY_CODE)

    @JvmStatic
    fun setSelectedCategory(context: Context, categoryCode: Int) {
        getPrefs(context).edit { putInt(KEY_CATEGORY_ID, categoryCode) }
    }

    @JvmStatic
    fun getSelectedTeamId(context: Context): Int =
        context.getSharedPreferences(TEAM_PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TEAM_ID, 0)

    @JvmStatic
    fun getSelectedTeamName(context: Context): String? =
        context.getSharedPreferences(TEAM_PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEAM_NAME, null)

    @JvmStatic
    fun getSelectedTeamNumber(context: Context): String? =
        context.getSharedPreferences(TEAM_PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEAM_NUMBER, null)

    @JvmStatic
    fun persistTeamSelection(context: Context, teamId: Int, teamName: String?, teamNumber: String?) {
        context.getSharedPreferences(TEAM_PREF_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_TEAM_ID, teamId)
            putString(KEY_TEAM_NAME, teamName)
            putString(KEY_TEAM_NUMBER, teamNumber)
        }
        getPrefs(context).edit {
            putInt(KEY_TEAM_ID, teamId)
            putString(KEY_TEAM_NAME, teamName)
            putString(KEY_TEAM_NUMBER, teamNumber)
        }
    }

    @JvmStatic
    fun clearTeamSelection(context: Context) {
        context.getSharedPreferences(TEAM_PREF_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_TEAM_ID)
            remove(KEY_TEAM_NAME)
            remove(KEY_TEAM_NUMBER)
        }
        getPrefs(context).edit {
            remove(KEY_TEAM_ID)
            remove(KEY_TEAM_NAME)
            remove(KEY_TEAM_NUMBER)
        }
    }
}
