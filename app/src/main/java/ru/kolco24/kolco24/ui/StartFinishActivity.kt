package ru.kolco24.kolco24.ui

import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.databinding.ActivityStartFinishBinding
import java.text.SimpleDateFormat
import java.util.Date

class StartFinishActivity : AppCompatActivity() {
    private val db = AppDatabase.getDatabase(application)
    private lateinit var binding: ActivityStartFinishBinding
    private var team: Team? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    //timer
    private var countDownTimer: CountDownTimer? = null

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
                    binding.teamStartNumber.text = team!!.startNumber
                    binding.teamPaidCount.text = String.format("%.0f чел", team!!.paidPeople)

                    if (team!!.startTime != 0L) {
                        val startTime = Date(team!!.startTime)
                        val formattedDate = dateFormat.format(startTime)
                        binding.startTime.text = formattedDate
                        binding.startButton.isEnabled = false
                    } else {
                        binding.finishButton.isEnabled = false
                    }

                    if (team!!.finishTime != 0L) {
                        val startTime = Date(team!!.finishTime)
                        val formattedDate = dateFormat.format(startTime)
                        binding.finishTime.text = formattedDate
                        binding.finishButton.isEnabled = false
                    }

                }
            }
        }

        setupCountDownTimer()
        countDownTimer!!.start()

        binding.startButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                if (team != null) {
                    team!!.startTime = System.currentTimeMillis()
                    db.teamDao().update(team)
                    withContext(Dispatchers.Main) {
                        binding.startTime.text = dateFormat.format(team!!.startTime)
                        binding.startButton.isEnabled = false
                    }
                }
            }
        }

        binding.finishButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                if (team != null) {
                    team!!.finishTime = System.currentTimeMillis()
                    db.teamDao().update(team)
                    withContext(Dispatchers.Main) {
                        binding.finishTime.text = dateFormat.format(team!!.finishTime)
                        binding.finishButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun setupCountDownTimer() {
        countDownTimer = object : CountDownTimer(1000000, 500) {
            // 1000 milliseconds (1 second) interval
            override fun onTick(millisUntilFinished: Long) {
                val currentTime = Date(System.currentTimeMillis())
                if (team != null) {
                    if (team?.startTime != 0L) {

                        val timeDifference = if (team?.finishTime == 0L) {
                            System.currentTimeMillis() - team!!.startTime
                        } else {
                            team!!.finishTime - team!!.startTime
                        }

                        val seconds = timeDifference / 1000
                        val minutes = seconds / 60
                        val hours = minutes / 60

                        val timeDifferenceString =
                            String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
                        binding.currentTime.text = timeDifferenceString
                        binding.currentTimeDesc.text = "Время на дистанции"
                        return
                    }
                }
                binding.currentTime.text = dateFormat.format(currentTime)
            }

            override fun onFinish() {
                // show dialog
            }
        }
    }
}