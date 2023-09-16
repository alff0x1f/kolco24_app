package ru.kolco24.kolco24

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

class NfcPointActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var textView: TextView // Add this line

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_point)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        textView = findViewById(R.id.textView)
        handleIntent(intent)
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
                        // Display the NDEF message content
                        Toast.makeText(this, "NDEF Message: $text, $hexId", Toast.LENGTH_SHORT)
                            .show()
                        // I want show hexId in TextView, chatGPT do it here
                        textView.text = "Hex ID: $hexId"


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