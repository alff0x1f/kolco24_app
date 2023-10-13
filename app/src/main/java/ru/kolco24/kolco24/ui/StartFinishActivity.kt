package ru.kolco24.kolco24.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.databinding.ActivityStartFinishBinding
import java.text.SimpleDateFormat
import java.util.Date

class StartFinishActivity : AppCompatActivity() {
    private val db = AppDatabase.getDatabase(application)
    private lateinit var binding: ActivityStartFinishBinding
    private var team: Team? = null
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartFinishBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val teamId = intent.getIntExtra("teamId", 0)

        CoroutineScope(Dispatchers.IO).launch {
            team = db.teamDao().getTeamById(teamId)

            if (team != null) {
                // Switch back to the main thread to update UI components
                withContext(Dispatchers.Main) {
                    binding.teamName.text = team!!.teamname

                    if (team!!.startTime != 0L) {
                        val startTime = Date(team!!.startTime)
                        val formattedDate = dateFormat.format(startTime)
                        binding.startTime.text = formattedDate
                    }

                    if (team!!.startTime != 0L) {
                        val startTime = Date(team!!.startTime)
                        val formattedDate = dateFormat.format(startTime)
                        binding.finishTime.text = formattedDate
                    }

                }
            }
        }

    }
}