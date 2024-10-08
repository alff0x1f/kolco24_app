package ru.kolco24.kolco24

import android.app.AlertDialog
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.Checkpoint
import ru.kolco24.kolco24.data.entities.CheckpointTag
import ru.kolco24.kolco24.data.entities.MemberTag
import ru.kolco24.kolco24.data.entities.NfcCheck
import ru.kolco24.kolco24.databinding.FragmentNfcPointBinding
import kotlin.math.roundToInt

class CheckpointVisitVerifyFragment : Fragment(), NfcAdapter.ReaderCallback {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var binding: FragmentNfcPointBinding

    private var pointId: String? = null // TODO remove this
    private var pointNumber: Int = 0 // TODO remove this

    private val memberTags = mutableListOf<MemberTag>()
    private var checkpointTag: CheckpointTag? = null
    private var checkpoint: Checkpoint? = null

    private var readMemberTags = false
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
            val checkpointTagId = arguments?.getInt("checkpointTagId", 0)
            if (checkpointTagId != null && checkpointTagId != 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    checkpointTag = db.pointTagDao().getPointTagById(checkpointTagId)
                    checkpointTag?.let {
                        checkpoint = db.pointDao().getPointById(it.checkpointId)
                        checkpoint?.let {
                            pointNumber = checkpoint!!.number
                            arguments?.putInt(
                                "pointNumber", pointNumber
                            )

                            withContext(Dispatchers.Main) {
                                binding.pointNumber.text = String.format("%02d", pointNumber)
                            }
                        }

                    }
                }
            } else {
                binding.pointNumber.text = "?"
            }

            val memberTagId = arguments?.getInt("memberTagId")
            memberTagId?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    val memberTag = db.memberTagDao().getMemberTagById(memberTagId)
                    memberTag?.let {
                        memberTags.add(memberTag)
                        withContext(Dispatchers.Main) {
                            binding.members.text = "1) Чип ${memberTag.number}"
                            binding.members.isVisible = true
                        }
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
                                "Считайте чип ещё раз чтобы начать заново."
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
            // check if hexId is already in list
            for (i in 0 until memberTags.count()) {
                if (memberTags[i].tagId == hexId) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireActivity(),
                            "Участник уже добавлен",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
            }
            if (checkpointTag == null) {
                db.pointTagDao().getPointTagByUID(hexId)?.let { fetchedCheckpointTag ->
                    checkpointTag = fetchedCheckpointTag
                    checkpoint = db.pointDao().getPointById(fetchedCheckpointTag.checkpointId)
                    requireActivity().runOnUiThread {
                        binding.pointNumber.text = String.format("%02d", checkpoint!!.number)
                    }
                    // TODO save checkpointVisit if all members are checked
                    return
                }
            }

            val memberTag = db.memberTagDao().getMemberTagByUID(hexId)
            if (memberTag == null) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireActivity(),
                        "Неизвестный чип",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            // add hexId to list
            memberTags.add(memberTag)
            saveNfcCheck(hexId)

            val team = db.teamDao().getTeamById(teamId)
            val paidPeople = team?.paidPeople?.roundToInt() ?: 2

            requireActivity().runOnUiThread {
                countDownTimer?.cancel()
                setupCountDownTimer()
                countDownTimer?.start()

                val numberedMembers = memberTags.mapIndexed { index, element ->
                    "${index + 1}) Чип ${element.number}"
                }
                binding.members.text = numberedMembers.joinToString("\n")
                binding.members.isVisible = true
            }

            if (memberTags.count() >= paidPeople) {
                for (i in 0 until memberTags.count()) {
                    saveNfcCheck(memberTags[i].tagId)
                }
                db.photoDao().insert(
                    ru.kolco24.kolco24.data.entities.Photo(
                        teamId,
                        pointNumber,
                        "",
                        "",
                        "",
                        System.currentTimeMillis(),
                        memberTags.joinToString(", ")
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
