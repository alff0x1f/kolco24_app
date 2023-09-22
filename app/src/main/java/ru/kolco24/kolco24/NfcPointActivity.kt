package ru.kolco24.kolco24

import NfcCheckViewModel
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
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
import androidx.core.view.isVisible
import ru.kolco24.kolco24.data.NfcCheck
import ru.kolco24.kolco24.databinding.ActivityNfcPointBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class NfcPointActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityNfcPointBinding

    private var pointId: String? = null
    private var pointNumber: Int = 0
    private val members = mutableListOf<String>()

    //timer
    private var countDownTimer: CountDownTimer? = null
    private val countdownDuration: Long = 60000 // 30 seconds in milliseconds


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNfcPointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        handleIntent(intent)
        requestPermission()
        if (lastGalleryImageUri() != null) {
//            Toast.makeText(this, lastGalleryImageUri(), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Галерея недоступна", Toast.LENGTH_SHORT).show()
        }

        binding.button.setOnClickListener {
            finish()
        }

        countDownTimer = object : CountDownTimer(countdownDuration, 1000) {
            // 1000 milliseconds (1 second) interval
            override fun onTick(millisUntilFinished: Long) {
                // Update the UI with the remaining time
                binding.timerTextView.text = (millisUntilFinished / 1000).toString()
                binding.progressBar.progress = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                // Countdown finished, finish the activity
                finish()
            }
        }

        (countDownTimer as CountDownTimer).start()
    }

    class MyReaderCallback(private val activity: NfcPointActivity) : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag?) {
            // toast tag id
            val tagId = tag?.id
            val ndef = Ndef.get(tag)
            val hexId = byteArrayToHexString(tagId!!)

            if (ndef == null) {
                // add hexId to list
                if (!activity.members.contains(hexId)) {
                    activity.members.add(hexId)

                    saveNfcCheck(hexId)

                    activity.runOnUiThread {
                        Toast.makeText(activity, "Участник добавлен", Toast.LENGTH_SHORT).show()
                        val numberedMembers = activity.members.mapIndexed { index, element ->
                            "${index + 1}) $element"
                        }
                        activity.binding.members.text = numberedMembers.joinToString("\n")
                        activity.binding.members.isVisible = true
                        if (activity.members.count() >= 2) {
                            activity.binding.button.isEnabled = true
                        } else {
                            activity.binding.button.isEnabled = false
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
        private fun saveNfcCheck(hexId: String){
            println("saveNfcCheck")
            val nfcCheckViewModel = NfcCheckViewModel(activity.application)
            val currTime = SimpleDateFormat(
                "dd.MM HH:mm",
                Locale.US
            ).format(Date())
            val nfcCheck = NfcCheck(activity.pointId, activity.pointNumber, hexId, currTime)
            nfcCheckViewModel.insert(nfcCheck)

            nfcCheckViewModel.getNotSyncNfcCheck().forEach {
                println("not sync: ${it.id} ${it.pointNfc} ${it.pointNumber} ${it.memberNfcId}")
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
        nfcAdapter.enableReaderMode(this,
            MyReaderCallback(activity = this),
            NfcAdapter.FLAG_READER_NFC_A, null)
    }

    override fun onPause() {
        super.onPause()
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcAdapter.disableReaderMode(this)
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
            var hexId = "-"

            if (tag != null) {
                val tagId = tag.id
                // Convert the byte array to a hex string
                hexId = byteArrayToHexString(tagId)
            }

            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMessages != null) {
                for (rawMessage in rawMessages) {
                    val message = rawMessage as NdefMessage
                    for (record in message.records) {
                        val payload = record.payload
                        val text = String(payload)
                        if (record.toMimeType() == "kolco24/point") {
                            pointId = hexId
                            pointNumber = text.toInt()
                            binding.pointNumber.text = text
                        }
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