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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.MemberTag
import ru.kolco24.kolco24.ui.members.MemberTagAdapter
import java.io.IOException


class AddTagActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var nfcAdapter: NfcAdapter
    private var currentTagId: String? = null
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_add_tag)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val tagIdTextView = findViewById<TextView>(R.id.tag_id_text)
        if (nfcAdapter == null) {
            tagIdTextView.text = "NFC not supported on this device"
            // NFC is not supported;
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_SHORT).show()
        } else {
            // Check if NFC is enabled
            if (!nfcAdapter.isEnabled) {
                tagIdTextView.text = "Включите NFC в настройках вашего телефона"
                Toast.makeText(
                    this,
                    "Пожалуйста, включите NFC в настройках вашего телефона",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        db = AppDatabase.getDatabase(applicationContext)

        // recycle view
        val memberTagsLiveData = db.memberTagDao().getAllMemberTagsLiveData()
        val recyclerView = findViewById<RecyclerView>(R.id.memberTagListView)
        memberTagsLiveData.observe(this) {
            val adapter = MemberTagAdapter(memberTagsLiveData)
            recyclerView.adapter = adapter
            recyclerView.layoutManager = LinearLayoutManager(this)
        }
        // swipe to refresh
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.swipeToRefresh)
        swipeRefreshLayout.setOnRefreshListener {
            fetchMemberTagsFromServer()
            swipeRefreshLayout.isRefreshing = false
        }

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
            db.memberTagDao().getMemberTagByUID(currentTagId!!)?.let { memberTag ->
                runOnUiThread {
                    findViewById<TextView>(R.id.header_text).apply {
                        text = "${memberTag.number}"
                    }
                }
            } ?: runOnUiThread {
                findViewById<TextView>(R.id.header_text).apply {
                    text = "Добавление меток"
                }
            }
        }
    }

    private fun sendTagDataToServer(tagId: String, tagNumber: Int) {
        val requestJson = JSONObject().apply {
            put("tag_id", tagId)
            put("number", tagNumber)
        }

        val body =
            requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = okhttp3.Request.Builder()
            .url("https://kolco24.ru/api/member_tag/")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tag_id_text).apply {
                        text = "Ошибка сохранения: ${e.message}"
                        setTextColor(resources.getColor(R.color.colorRed))
                    }
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val responseJson = JSONObject(response.body?.string())

                    val memberTag = MemberTag(
                        id = responseJson.getInt("id"),
                        tagId = responseJson.getString("tag_id"),
                        number = responseJson.getInt("number"),
                    )
                    db.memberTagDao().insertMemberTag(memberTag)
                }
                runOnUiThread {
                    if (response.isSuccessful) {
                        findViewById<TextView>(R.id.tag_id_text).apply {
                            text = "Метка сохранена"
                            setTextColor(resources.getColor(R.color.colorGreen))
                        }
                        findViewById<EditText>(R.id.tag_number_input).apply {
                            setText("")
                        }
                    } else {
                        findViewById<TextView>(R.id.tag_id_text).apply {
                            text = "Ошибка сохранения: ${response.body?.string()}"
                            setTextColor(resources.getColor(R.color.colorRed))
                        }
                    }
                }
            }
        })
    }


    // Function to fetch member tags from the server
    private fun fetchMemberTagsFromServer() {
        // Build the GET request
        val request = okhttp3.Request.Builder()
            .url("https://kolco24.ru/api/member_tag/")
            .build()

        // Make the network call
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Handle the failure case
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@AddTagActivity,
                        "Ошибка получения данных: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                // Handle the response
                if (response.isSuccessful) {
                    response.body?.let { responseBody ->
                        try {
                            // Step 5: Parse the response body as JSON Array
                            val jsonArray = JSONArray(responseBody.string())

                            // Step 6: Iterate through the array and parse each JSON object
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)

                                // Create a MemberTag object
                                val memberTag = MemberTag(
                                    id = jsonObject.getInt("id"),
                                    number = jsonObject.getInt("number"),
                                    tagId = jsonObject.getString("tag_id")
                                )
                                // Launch a coroutine to interact with the database
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        // Check if the record with the same ID already exists in the database
                                        val existingMemberTag =
                                            db.memberTagDao().getMemberTagById(memberTag.id)

                                        if (existingMemberTag == null) {
                                            // No existing record, insert the new MemberTag
                                            db.memberTagDao().insertMemberTag(memberTag)
                                        } else {
                                            // Existing record found, check if the number or tagId is different
                                            if (existingMemberTag.number != memberTag.number || existingMemberTag.tagId != memberTag.tagId) {
                                                // Update the record since there are differences
                                                db.memberTagDao().updateMemberTag(memberTag)
                                            } else {
                                                println("Skipping: No changes for MemberTag ID ${memberTag.id}")
                                            }
                                        }
                                    }
                                }
                            }
                            runOnUiThread {
                                Toast.makeText(
                                    this@AddTagActivity,
                                    "Метки участников загружены",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                        } catch (e: Exception) {
                            // Handle JSON parsing errors
                            runOnUiThread {
                                Toast.makeText(
                                    this@AddTagActivity,
                                    "Ошибка обработки данных: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    // Handle non-successful response
                    runOnUiThread {
                        Toast.makeText(
                            this@AddTagActivity,
                            "Ошибка получения данных: ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
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