package ru.kolco24.kolco24

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException


class AddTagActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var nfcAdapter: NfcAdapter
    private var currentTagId: String? = null
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_tag)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            findViewById<TextView>(R.id.tag_id_text).text = "NFC not supported on this device"
            // NFC is not supported;
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_SHORT).show()
        } else {

            // Check if NFC is enabled
            if (!nfcAdapter.isEnabled) {
                findViewById<TextView>(R.id.tag_id_text).text =
                    "Please enable NFC in your phone settings"
                Toast.makeText(
                    this,
                    "Пожалуйста, включите NFC в настройках вашего телефона",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val tagIdTextView = findViewById<TextView>(R.id.tag_id_text)
        val tagNumberInput = findViewById<EditText>(R.id.tag_number_input)
        val submitButton = findViewById<Button>(R.id.submit_button)

        submitButton.setOnClickListener {
            val tagNumber = tagNumberInput.text.toString().toIntOrNull()
            if (currentTagId != null && tagNumber != null) {
                sendTagDataToServer(currentTagId!!, tagNumber)
            } else {
                tagIdTextView.text = "Please scan a tag and enter a valid number."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Enable NFC Reader Mode
        nfcAdapter.enableReaderMode(
            this,
            this,  // The activity itself acts as the reader callback
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        // Disable NFC Reader Mode
        nfcAdapter.disableReaderMode(this)
    }

    // This method is called when a new NFC tag is detected
    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            currentTagId = bytesToHex(tag.id)

            // Update UI on the main thread
            runOnUiThread {
                findViewById<TextView>(R.id.tag_id_text).apply {
                    text = "Tag ID: $currentTagId"
                    setTextColor(resources.getColor(R.color.textContrast))
                }
                findViewById<EditText>(R.id.tag_number_input).apply {
                    setText("")
                    requestFocus()
                    post {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
    }

    private fun sendTagDataToServer(tagId: String, tagNumber: Int) {
        val json = JSONObject().apply {
            put("tag_id", tagId)
            put("number", tagNumber)
            put("race", 2)
        }

        val body =
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://kolco24.ru/api/race/2/point_tags/")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tag_id_text).apply {
                        text = "Failed to send data: ${e.message}"
                        setTextColor(resources.getColor(R.color.colorRed))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        findViewById<TextView>(R.id.tag_id_text).apply {
                            text = "Метка сохранена"
                            setTextColor(resources.getColor(R.color.colorGreen))
                        }
                    } else {
                        findViewById<TextView>(R.id.tag_id_text).apply {
                            text = "Failed to send data: ${response.body?.string()}"
                            setTextColor(resources.getColor(R.color.colorRed))
                        }
                    }
                }
            }
        })
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val i = byte.toInt()
            result.append(hexChars[i shr 4 and 0x0F])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}