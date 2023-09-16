package ru.kolco24.kolco24

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val context = preferenceManager.context

            val notificationPreference = SwitchPreferenceCompat(context)
            notificationPreference.key = "notifications"
            notificationPreference.title = "Enable message notifications"

            val notificationCategory = PreferenceCategory(context)
            notificationCategory.key = "notifications_category"
            notificationCategory.title = "Notifications2"
            preferenceScreen.addPreference(notificationCategory)
            notificationCategory.addPreference(notificationPreference)

            val feedbackPreference = Preference(context)
            feedbackPreference.key = "feedback"
            feedbackPreference.title = "Send feedback"
            feedbackPreference.summary = "Report technical issues or suggest new features"

            val helpCategory = PreferenceCategory(context)
            helpCategory.key = "help"
            helpCategory.title = "Help2"
            preferenceScreen.addPreference(helpCategory)
            helpCategory.addPreference(feedbackPreference)
        }
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}