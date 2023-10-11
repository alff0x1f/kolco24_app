package ru.kolco24.kolco24.ui.members

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.MemberTag
import ru.kolco24.kolco24.databinding.ActivityAddMemberTagBinding


class AddMemberTagActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityAddMemberTagBinding
    private val db = AppDatabase.getDatabase(application)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddMemberTagBinding.inflate(layoutInflater)

        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val memberTagsLiveData = db.memberTagDao().getAllMemberTagsLiveData()
        memberTagsLiveData.observe(this, Observer {
            val adapter = MemberTagAdapter(memberTagsLiveData)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
        })
    }

    class MyReaderCallback(
        private val context: Context,
        private val activity: AddMemberTagActivity
    ) :
        NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag?) {
            // toast tag id
            val tagId = tag?.id

            val tagHex = byteArrayToHexString(tagId!!)
            val t = MemberTag(
                null,
                tagHex,
            )

            val existingTag = activity.db.memberTagDao().getMemberTagByTagId(tagHex)
            if (existingTag == null) {
                activity.db.memberTagDao().insertMemberTag(t)
            }
            activity.runOnUiThread {
                activity.binding.lastTagTextView.text = tagHex
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
            AddMemberTagActivity.MyReaderCallback(context = this, activity = this),
            NfcAdapter.FLAG_READER_NFC_A,
            null
        )
    }
}