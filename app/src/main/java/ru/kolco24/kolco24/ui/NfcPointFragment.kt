package ru.kolco24.kolco24

import NfcCheckViewModel
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.NfcCheck
import ru.kolco24.kolco24.databinding.FragmentNfcPointBinding
import ru.kolco24.kolco24.ui.teams.TeamViewModel
import kotlin.math.roundToInt

class NfcPointFragment : Fragment(), NfcAdapter.ReaderCallback {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var binding: FragmentNfcPointBinding
    private var pointId: String? = null
    private var pointNumber: Int = 0
    private val members = mutableListOf<String>()
    private var readMemberTags = true
    private var teamId: Int = 0

    //timer
    private var countDownTimer: CountDownTimer? = null
    private val countdownDuration: Long = 20000 // 20 seconds in milliseconds

    private lateinit var db: AppDatabase

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        db = AppDatabase.getDatabase(requireContext())
        binding = FragmentNfcPointBinding.inflate(inflater, container, false)

        arguments?.let {
            val tagId = arguments?.getString("tagId") ?: ""
            lifecycleScope.launch(Dispatchers.IO) {
                val pointTag = db.pointTagDao().getPointTagByTag(tagId)
                pointTag?.let {
                    pointNumber = db.pointDao().getPointById(it.pointId).number
                    println("pointNumber: $pointNumber")
                    withContext(Dispatchers.Main) {
                        binding.pointNumber.text = String.format("%02d", pointNumber)
                    }
                }
            }
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        binding.button.setOnClickListener { navigateBack() }

        setupCountDownTimer()
        (countDownTimer as CountDownTimer).start()

        //putInt("pointNumber", pointNumber)
        // extract arguments
        arguments?.let {
            pointId = it.getString("pointId")
            pointNumber = it.getInt("pointNumber")
            teamId = it.getInt("teamId")
        }

        Toast.makeText(requireContext(), "Point ID: $pointNumber", Toast.LENGTH_SHORT).show()
    }


    override fun onResume() {
        super.onResume()
        nfcAdapter.enableReaderMode(
            requireActivity(),
            this,
            NfcAdapter.FLAG_READER_NFC_A,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        println("onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
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
                if (!isAdded) {
                    // fragment is not added to activity, no need to show dialog
                    return
                }
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Время истекло")
                    .setMessage(
                        "Нужно за ${countdownDuration / 1000} секунд отметить всех участников. " +
                                "Считайте метку на КП ещё раз " +
                                "чтобы начать заново."
                    )
                    .setPositiveButton(
                        "Ок"
                    ) { dialogInterface, i -> navigateBack() }
                    .setOnCancelListener { navigateBack() }.create()
                dialog.show()
            }
        }
    }


    private fun navigateBack() {
        val navController = findNavController()
        if (!navController.popBackStack()) {
            // Optionally handle the case where no fragments are left in the stack
            activity?.finish()
        }
        println("Navigating back from NfcPointFragment")
    }


    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            // toast tag id
            println("NfcPointTagFragment: Tag ID: ${bytesToHex(tag.id)}")
            val tagId = tag.id
            val ndef = Ndef.get(tag)
            val hexId = bytesToHex(tagId)
//            val activity = requireActivity() as MainActivity

            if (readMemberTags) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireActivity(),
                        "Все участники отмечены",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
            // add hexId to list
            if (!members.contains(hexId)) {
                members.add(hexId)
                saveNfcCheck(hexId)

                val team = db.teamDao().getTeamById(teamId)
                val paidPeople = team?.paidPeople?.roundToInt() ?: 2

                requireActivity().runOnUiThread {
                    countDownTimer?.cancel()
                    setupCountDownTimer()
                    countDownTimer?.start()

                    val numberedMembers = members.mapIndexed { index, element ->
                        "${index + 1}) Участник ${index + 1}"
                    }
                    binding.members.text = numberedMembers.joinToString("\n")
                    binding.members.isVisible = true
                }

                if (members.count() >= paidPeople) {
                    for (i in 0 until members.count()) {
                        saveNfcCheck(members[i])
                    }
                    db.photoDao().insert(
                        ru.kolco24.kolco24.data.entities.Photo(
                            teamId,
                            pointNumber,
                            "",
                            "",
                            "",
                            System.currentTimeMillis(),
                            members.joinToString(", ")
                        )
                    )

                    db.photoDao().getListPhotos().forEach {
                        println("photo: ${it.id} ${it.teamId} ${it.pointNumber} ${it.photoUrl} ${it.photoThumbUrl} ${it.photoTime} ${it.time} ${it.pointNfc}")
                    }

                    countDownTimer?.cancel()
                    readMemberTags = false

                    requireActivity().runOnUiThread {
                        binding.doneIcon.isVisible = true
                        binding.timerTextView.isVisible = false
                        binding.circularProgressBar.isVisible = false
                        binding.button.text = "Закрыть"
                        binding.helperTextView.text = "Все участники отмечены"
                    }
                }
            } else {
                requireActivity().runOnUiThread {
                    Toast.makeText(activity, "Участник уже добавлен", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * save value to room database
     */
    private fun saveNfcCheck(hexId: String) {
        System.out.println(System.currentTimeMillis())

        if (pointId != null) {
            val nfcCheck = NfcCheck(
                pointId!!,
                pointNumber,
                hexId,
                System.currentTimeMillis()
            )
            db.nfcCheckDao().insert(nfcCheck)
        }

        db.nfcCheckDao().getNotSyncNfcCheck().forEach {
            println("not sync: ${it.id} ${it.pointNfc} ${it.pointNumber} ${it.memberNfcId} ${it.time}")
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
