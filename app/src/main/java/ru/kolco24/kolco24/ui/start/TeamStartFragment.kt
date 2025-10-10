package ru.kolco24.kolco24.ui.start

import android.media.AudioAttributes
import android.media.SoundPool
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
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
import ru.kolco24.kolco24.data.entities.MemberTag
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.data.entities.TeamStart
import ru.kolco24.kolco24.databinding.FragmentTeamStartBinding
import ru.kolco24.kolco24.sync.TeamStartUploader
import java.util.Locale

class TeamStartFragment : Fragment(), NfcAdapter.ReaderCallback {

    private var _binding: FragmentTeamStartBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TeamStartViewModel by viewModels()
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var db: AppDatabase
    private var soundPool: SoundPool? = null
    private var soundScan: Int = 0
    private var soundErr: Int = 0
    private var soundDone: Int = 0

    private val scannedAdapter = ScannedTagAdapter()
    private val pendingAdapter = TeamStartAdapter()

    private val scannedTags = mutableListOf<MemberTag>()
    private var currentTeam: Team? = null
    @Volatile private var lastScanTimestamp: Long = 0
    private val scanCooldownMs = 600L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTeamStartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getDatabase(requireContext())
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        initSounds()
        (activity as? MainActivity)?.getNavView()?.isVisible = false

        binding.scannedTagsList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = scannedAdapter
        }

        binding.pendingList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pendingAdapter
        }

        viewModel.allStarts.observe(viewLifecycleOwner) { items ->
            pendingAdapter.submit(items)
            binding.pendingHeader.isVisible = items.isNotEmpty()
        }

        binding.buttonFindTeam.setOnClickListener { lookupTeam() }
        binding.buttonClear.setOnClickListener { clearAll() }
        binding.buttonStart.setOnClickListener { persistStart() }

        binding.swipeToRefresh.setOnRefreshListener { syncStarts() }

        binding.inputStartNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                lookupTeam(auto = true)
            }
        })

        updateTeamInfo(null)
        updateScannedStatus()
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

    private fun lookupTeam(auto: Boolean = false) {
        val number = binding.inputStartNumber.text?.toString()?.trim().orEmpty()
        if (number.isBlank()) {
            if (!auto) {
                Toast.makeText(requireContext(), R.string.team_start_toast_no_team, Toast.LENGTH_SHORT).show()
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val found = viewModel.findTeamByStartNumber(number)
            withContext(Dispatchers.Main) {
                if (found == null) {
                    if (!auto) {
                        Toast.makeText(requireContext(), R.string.team_start_toast_no_team, Toast.LENGTH_SHORT).show()
                    }
                    updateTeamInfo(null)
                } else {
                    applyTeam(found)
                }
            }
        }
    }

    private fun applyTeam(team: Team) {
        currentTeam = team
        updateTeamInfo(team)
        clearScannedTags()
        binding.buttonStart.isEnabled = true
    }

    private fun clearAll() {
        currentTeam = null
        binding.inputStartNumber.setText("")
        clearScannedTags()
        updateTeamInfo(null)
        binding.buttonStart.isEnabled = false
    }

    private fun clearScannedTags() {
        scannedTags.clear()
        scannedAdapter.submit(emptyList())
        updateScannedStatus()
    }

    private fun updateTeamInfo(team: Team?) {
        if (team == null) {
            binding.teamInfo.text = getString(R.string.team_start_info_placeholder)
            binding.buttonStart.isEnabled = false
        } else {
            binding.teamInfo.text = getString(
                R.string.team_start_entry_title,
                team.startNumber.ifBlank { "?" },
                team.teamname
            )
            binding.buttonStart.isEnabled = true
        }
    }

    private fun updateScannedStatus() {
        val expected = currentTeam?.ucount ?: 0
        val scanned = scannedTags.size
        binding.scanStatus.text = if (currentTeam == null) {
            getString(R.string.team_start_scan_placeholder)
        } else {
            getString(R.string.team_start_toast_tag_loaded, scanned, expected)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        val now = System.currentTimeMillis()
        val allowScan = synchronized(this) {
            if (now - lastScanTimestamp < scanCooldownMs) {
                false
            } else {
                lastScanTimestamp = now
                true
            }
        }
        if (!allowScan) return
        val hex = bytesToHex(tag.id)
        val team = currentTeam
        if (team == null) {
            playErrorFeedback()
            showToast(R.string.team_start_toast_no_team)
            return
        }
        if (scannedTags.any { it.tagId.equals(hex, ignoreCase = true) }) {
            playErrorFeedback()
            showToast(R.string.team_start_toast_duplicate_tag)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val memberTag = db.memberTagDao().getMemberTagByUID(hex)
            if (memberTag == null) {
                playErrorFeedback()
                showToast(R.string.team_start_toast_unknown_tag)
                return@launch
            }
            withContext(Dispatchers.Main) {
                scannedTags.add(memberTag)
                val labels = scannedTags.mapIndexed { index, item ->
                    String.format(Locale.getDefault(), "%d) %s", index + 1, item.number)
                }
                scannedAdapter.submit(labels)
                updateScannedStatus()
                playScanFeedback()
            }
        }
    }

    private fun persistStart() {
        val team = currentTeam
        if (team == null) {
            Toast.makeText(requireContext(), R.string.team_start_toast_no_team, Toast.LENGTH_SHORT).show()
            return
        }
        if (scannedTags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.team_start_toast_unknown_tag, Toast.LENGTH_SHORT).show()
            return
        }
        val event = TeamStart(
            teamId = team.id,
            startNumber = team.startNumber,
            teamName = team.teamname,
            participantCount = team.ucount,
            scannedCount = scannedTags.size,
            memberTags = scannedTags.joinToString(",") { it.tagId },
            startTimestamp = System.currentTimeMillis()
        )
        viewModel.insert(event)
        Toast.makeText(
            requireContext(),
            getString(R.string.team_start_toast_saved, team.teamname),
            Toast.LENGTH_SHORT
        ).show()
        clearScannedTags()
        playSuccessFeedback()
    }

    private fun syncStarts() {
        binding.swipeToRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val useLocal = SettingsPreferences.shouldUseLocalServer(requireContext())
            val uploader = TeamStartUploader(requireContext())
            val result = uploader.uploadPending(useLocal)
            withContext(Dispatchers.Main) {
                binding.swipeToRefresh.isRefreshing = false
                val msgRes = if (result) R.string.team_start_toast_upload_ok else R.string.team_start_toast_upload_error
                Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showToast(resId: Int) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
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

    private fun playScanFeedback() {
        soundPool?.play(soundScan, 1f, 1f, 0, 0, 1f)
        vibrate(success = true)
    }

    private fun playErrorFeedback() {
        soundPool?.play(soundErr, 1f, 1f, 0, 0, 1f)
        vibrate(success = false)
    }

    private fun playSuccessFeedback() {
        soundPool?.play(soundDone, 1f, 1f, 0, 0, 1f)
        vibrate(success = true)
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
}
