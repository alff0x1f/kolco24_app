package ru.kolco24.kolco24

import NfcCheckViewModel
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.NfcCheck
import ru.kolco24.kolco24.databinding.ActivityNfcPointBinding
import ru.kolco24.kolco24.ui.legends.PointViewModel
import ru.kolco24.kolco24.ui.teams.TeamViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt


class NfcPointActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityNfcPointBinding

    private var pointId: String? = null
    private var pointNumber: Int = 0
    private val members = mutableListOf<String>()
    private var readMemberTags = true

    private var teamId: Int = 0
    private var teamViewModel: TeamViewModel? = null

    //timer
    private var countDownTimer: CountDownTimer? = null
    private val countdownDuration: Long = 20000 // 20 seconds in milliseconds

    //model
    private var pointViewModel: PointViewModel? = null
    private val db = AppDatabase.getDatabase(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNfcPointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        handleIntent(intent)
        teamId = this.baseContext.getSharedPreferences("team", MODE_PRIVATE).getInt("team_id", 0)
        teamViewModel = TeamViewModel(application)

        // gallery playground
//        requestPermission()
//        if (lastGalleryImageUri() != null) {
////            Toast.makeText(this, lastGalleryImageUri(), Toast.LENGTH_SHORT).show()
//        } else {
//            Toast.makeText(this, "Галерея недоступна", Toast.LENGTH_SHORT).show()
//        }

        binding.button.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
        setupCountDownTimer()
        (countDownTimer as CountDownTimer).start()

        pointViewModel = PointViewModel(application)
    }

    private fun setupCountDownTimer() {
        countDownTimer = object : CountDownTimer(countdownDuration, 100) {
            // 1000 milliseconds (1 second) interval
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000.0
                val formattedTime = String.format("%.1f", secondsRemaining)
                val progress = (millisUntilFinished.toFloat() / countdownDuration) * 100
                val color = calculateColor(progress)

                binding.timerTextView.text = formattedTime
                binding.timerTextView.setTextColor(color)
                binding.circularProgressBar.progress = progress
                binding.circularProgressBar.progressBarColor = color
            }

            fun calculateColor(progress: Float): Int {
                var hue = 140F
                if (progress < 50F) {
                    hue = progress * 2.8F
                }
                val color = ColorUtils.HSLToColor(floatArrayOf(hue, 1F, 0.4F))
                return color
            }

            override fun onFinish() {
                // show dialog
                val dialog = AlertDialog.Builder(this@NfcPointActivity)
                    .setTitle("Время истекло")
                    .setMessage(
                        "Нужно за ${countdownDuration / 1000} секунд отметить всех участников. " +
                                "Считайте метку на КП ещё раз " +
                                "чтобы начать заново."
                    )
                    .setPositiveButton(
                        "Ок"
                    ) { dialogInterface, i -> finish() }.setOnCancelListener { finish() }.create()
                dialog.show()
            }
        }
    }

    private fun sendTagToServer(pointId: String, pointNumber: Int) {
        val dataDownloader = DataDownloader(application)
        dataDownloader.uploadTag(pointId, pointNumber)
    }

    class MyReaderCallback(private val context: Context, private val activity: NfcPointActivity) :
        NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag?) {
            // toast tag id
            val tagId = tag?.id
            val ndef = Ndef.get(tag)
            val hexId = byteArrayToHexString(tagId!!)

            if (ndef == null) {
                if (!activity.readMemberTags) {
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "Все участники отмечены",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
                // add hexId to list
                if (!activity.members.contains(hexId)) {
                    activity.members.add(hexId)

                    saveNfcCheck(hexId)

                    val team = activity.teamViewModel?.getTeamById(activity.teamId)
                    val paidPeople = team?.paidPeople?.roundToInt() ?: 2

                    activity.runOnUiThread {
                        activity.countDownTimer?.cancel()
                        activity.setupCountDownTimer()
                        activity.countDownTimer?.start()

                        val numberedMembers = activity.members.mapIndexed { index, element ->
                            "${index + 1}) $element"
                        }
                        activity.binding.members.text = numberedMembers.joinToString("\n")
                        activity.binding.members.isVisible = true
                    }

                    if (activity.members.count() >= paidPeople) {
                        for (i in 0 until activity.members.count()) {
                            saveNfcCheck(activity.members[i])
                        }
                        activity.db.photoDao().insert(
                            ru.kolco24.kolco24.data.entities.Photo(
                                activity.teamId,
                                activity.pointNumber,
                                "",
                                "",
                                "",
                                System.currentTimeMillis(),
                                activity.members.joinToString(", ")
                            )
                        )

                        activity.db.photoDao().getListPhotos().forEach {
                            println("photo: ${it.id} ${it.teamId} ${it.pointNumber} ${it.photoUrl} ${it.photoThumbUrl} ${it.photoTime} ${it.time} ${it.pointNfc}")
                        }

                        activity.countDownTimer?.cancel()
                        activity.readMemberTags = false

                        activity.runOnUiThread {
                            activity.binding.doneIcon.isVisible = true
                            activity.binding.timerTextView.isVisible = false
                            activity.binding.circularProgressBar.isVisible = false
                            activity.binding.button.text = "Закрыть"
                            activity.binding.helperTextView.text = "Все участники отмечены"
                        }
                    }
                } else {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Участник уже добавлен", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        /**
         * save value to room database
         */
        private fun saveNfcCheck(hexId: String) {
            val nfcCheckViewModel = NfcCheckViewModel(activity.application)

            System.out.println(System.currentTimeMillis())

            if (activity.pointId != null) {
                val nfcCheck = NfcCheck(
                    activity.pointId!!,
                    activity.pointNumber,
                    hexId,
                    System.currentTimeMillis()
                )
                nfcCheckViewModel.insert(nfcCheck)
                System.out.println(nfcCheck.id)
            }

            nfcCheckViewModel.getNotSyncNfcCheck().forEach {
                println("not sync: ${it.id} ${it.pointNfc} ${it.pointNumber} ${it.memberNfcId} ${it.time}")
            }
        }

        private fun byteArrayToHexString(byteArray: ByteArray): String {
            val hexString = StringBuilder()
            for (b in byteArray) {
                val hex = Integer.toHexString(0xFF and b.toInt())
                if (hex.length == 1) {
                    hexString.append('0')
                }
                hexString.append(hex)
            }
            return hexString.toString()
        }
    }

    override fun onResume() {
        super.onResume()

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcAdapter.enableReaderMode(
            this,
            MyReaderCallback(context = this, activity = this),
            NfcAdapter.FLAG_READER_NFC_A,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcAdapter.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }


    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES),
                    1
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                //
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                    1
                )
            }
        }
    }

    private fun lastGalleryImageUri(): String? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Images.Media._ID + " DESC"
        )
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            if (index == -1) {
                cursor.close()
                return null
            }
            val id = cursor.getLong(index)
            cursor.close()
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendPath(id.toString()).toString()
        }
        return null
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag == null) {
                Toast.makeText(this, "tag is null", Toast.LENGTH_SHORT).show()
                return
            }
            // Convert the byte array to a hex string
            val hexId = byteArrayToHexString(tag.id)

            // Use a coroutine to perform database operations asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                val pointTag = db.pointTagDao().getPointTagByTag(hexId)
                pointTag?.let {
                    pointId = it.tag
                    pointNumber = db.pointDao().getPointById(it.pointId).number

                    // Switch back to the main thread to update UI components
                    withContext(Dispatchers.Main) {
                        binding.pointNumber.text = String.format("%02d", pointNumber)
                    }
                }
                if (pointTag == null) {
                    // show dialog and finish activity
                    withContext(Dispatchers.Main) {
                        binding.pointNumber.text = "?"
                        val dialog = AlertDialog.Builder(this@NfcPointActivity)
                            .setTitle("Ошибка")
                            .setMessage("Неизвестная метка")
                            .setPositiveButton(
                                "Ок"
                            ) { dialogInterface, i -> finish() }.setOnCancelListener { finish() }
                            .create()
                        dialog.show()
                    }
                }
            }
        }
    }

    private fun byteArrayToHexString(byteArray: ByteArray): String {
        val hexString = StringBuilder()
        for (b in byteArray) {
            val hex = Integer.toHexString(0xFF and b.toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }
}