package ru.kolco24.kolco24.ui.finish

import android.media.AudioAttributes
import android.media.SoundPool
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.SettingsPreferences
import ru.kolco24.kolco24.data.entities.TeamFinish
import ru.kolco24.kolco24.databinding.FragmentTeamFinishBinding
import ru.kolco24.kolco24.sync.TeamFinishUploader

class TeamFinishFragment : Fragment(), NfcAdapter.ReaderCallback {

    private var _binding: FragmentTeamFinishBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TeamFinishViewModel by viewModels()
    private lateinit var db: AppDatabase
    private lateinit var nfcAdapter: NfcAdapter

    private val adapter = TeamFinishAdapter()
    private var soundPool: SoundPool? = null
    private var soundScan: Int = 0
    private var soundErr: Int = 0
    private var soundDone: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTeamFinishBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getDatabase(requireContext())
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        initSounds()
        (activity as? MainActivity)?.getNavView()?.isVisible = false

        binding.pendingFinishList.layoutManager = LinearLayoutManager(requireContext())
        binding.pendingFinishList.adapter = adapter

        viewModel.allFinishes.observe(viewLifecycleOwner) { items ->
            adapter.submit(items)
            binding.pendingFinishHeader.isVisible = items.isNotEmpty()
        }

        binding.buttonClearFinish.setOnClickListener { clearCurrent() }
        binding.swipeToRefreshFinish.setOnRefreshListener { syncFinishes() }
        updateCurrentTag(null)
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    override fun onDestroyView() {
        disableReaderMode()
        releaseSounds()
        (activity as? MainActivity)?.getNavView()?.isVisible = true
        _binding = null
        super.onDestroyView()
    }

    private fun enableReaderMode() {
        if (::nfcAdapter.isInitialized) {
            nfcAdapter.enableReaderMode(
                requireActivity(),
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null
            )
        }
    }

    private fun disableReaderMode() {
        if (::nfcAdapter.isInitialized) {
            nfcAdapter.disableReaderMode(requireActivity())
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        val hex = bytesToHex(tag.id)
        lifecycleScope.launch(Dispatchers.IO) {
            val member = db.memberTagDao().getMemberTagByUID(hex)
            val finish = TeamFinish(
                memberTagId = member?.id ?: 0,
                tagUid = hex,
                recordedAt = System.currentTimeMillis()
            )
            viewModel.insert(finish)
            withContext(Dispatchers.Main) {
                updateCurrentTag(finish)
                playSuccessFeedback()
                Toast.makeText(requireContext(), R.string.team_finish_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncFinishes() {
        binding.swipeToRefreshFinish.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val useLocal = SettingsPreferences.shouldUseLocalServer(requireContext())
            val uploader = TeamFinishUploader(requireContext())
            val result = uploader.uploadPending(useLocal)
            withContext(Dispatchers.Main) {
                binding.swipeToRefreshFinish.isRefreshing = false
                val msg = if (result) R.string.team_finish_upload_ok else R.string.team_finish_upload_error
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearCurrent() {
        updateCurrentTag(null)
    }

    private fun updateCurrentTag(event: TeamFinish?) {
        binding.currentTagInfo.text = event?.tagUid ?: getString(R.string.team_finish_placeholder)
    }

    private fun initSounds() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setAudioAttributes(attributes)
            .setMaxStreams(2)
            .build().apply {
                soundScan = load(requireContext(), R.raw.beep_scan, 1)
                soundErr = load(requireContext(), R.raw.beep_err, 1)
                soundDone = load(requireContext(), R.raw.beep_send, 1)
            }
    }

    private fun releaseSounds() {
        soundPool?.release()
        soundPool = null
    }

    private fun playSuccessFeedback() {
        soundPool?.play(soundDone, 1f, 1f, 0, 0, 1f)
        vibrate(true)
    }

    private fun vibrate(success: Boolean) {
        val vibrator = context?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = if (success) VibrationEffect.DEFAULT_AMPLITUDE else 96
            vibrator.vibrate(VibrationEffect.createOneShot(40, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
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
