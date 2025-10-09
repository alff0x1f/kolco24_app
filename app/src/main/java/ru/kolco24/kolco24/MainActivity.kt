package ru.kolco24.kolco24

import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.SettingsPreferences
import ru.kolco24.kolco24.data.entities.CheckpointTag
import ru.kolco24.kolco24.data.entities.MemberTag
import ru.kolco24.kolco24.databinding.ActivityMainBinding
import ru.kolco24.kolco24.sync.PhotoUploader
import ru.kolco24.kolco24.sync.UploadTarget
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcAdapter: NfcAdapter
    private val db: AppDatabase by lazy { AppDatabase.getDatabase(application) }
    private var soundPool: SoundPool? = null
    private var soundScan: Int = 0
    private var soundErr: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc != null) {
            nfcAdapter = nfc
        } else{
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_SHORT).show()
        }

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.navigation_taken_points,
            R.id.navigation_legends,
            R.id.navigation_settings
        ).build()
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration)
        NavigationUI.setupWithNavController(binding.navView, navController)

        // Add OnDestinationChangedListener to NavController
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nfcPointFragment) {
                // Hide BottomNavigationView when in NfcPointFragment
                binding.navView.visibility = View.GONE
            } else {
                // Show BottomNavigationView for other fragments
                binding.navView.visibility = View.VISIBLE
                enableNfcReaderMode()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            handleIntent(intent)
        }

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val dataDownloader = DataDownloader(application)
            dataDownloader.hideToasts()
            dataDownloader.setLocalDownload(true)
            dataDownloader.downloadCheckpoints()
            dataDownloader.downloadTeams(null)
        }

        if (::nfcAdapter.isInitialized){
            if (!nfcAdapter.isEnabled) {
                // NFC is disabled; show a dialog or message to prompt the user to enable it
                showNFCEnableDialog()
                return
            }
        }

        initSounds()

        CoroutineScope(Dispatchers.IO).launch {
            PhotoUploader(applicationContext).uploadPending(UploadTarget.REMOTE)
        }
    }

    fun appDatabase(): AppDatabase = db

    fun getNavView(): BottomNavigationView {
        return binding.navView
    }


    override fun onResume() {
        super.onResume()
        enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }

    private fun enableNfcReaderMode() {
        println("Enabling NFC reader mode in MainActivity")
        if (::nfcAdapter.isInitialized) {
            nfcAdapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
        }
    }

    private fun disableNfcReaderMode() {
        if (::nfcAdapter.isInitialized) {
            nfcAdapter.disableReaderMode(this)
        }
    }


    private fun showNFCEnableDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("NFC отключен")
        builder.setMessage("Пожалуйста, включите NFC в настройках вашего телефона")
        builder.setPositiveButton("Открыть настройки") { _, _ ->
            // Open device settings to enable NFC
            val settingsIntent = Intent(Settings.ACTION_NFC_SETTINGS)
            startActivity(settingsIntent)
        }
        builder.setNegativeButton("Отмена") { _, _ ->
            // Handle cancel or provide an alternative action
            // show a message or dialog that NFC is required to proceed
            Toast.makeText(this@MainActivity, "Сканирование меток недоступно", Toast.LENGTH_SHORT)
                .show()
        }
        val dialog = builder.create()
        dialog.show()
    }

    fun selectTeamRequiredDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Выберите команду")
        builder.setMessage("Нужно выбрать свою команду в настройках")
        builder.setPositiveButton("Выбрать") { _, _ ->
            binding.navView.setSelectedItemId(R.id.navigation_settings)
        }
        val dialog = builder.create()
        dialog.show()
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            val hexId = bytesToHex(tag.id)
            val teamId = SettingsPreferences.getSelectedTeamId(this)
            if (teamId == 0) {
                runOnUiThread {
                    playErrorFeedback()
                    selectTeamRequiredDialog()
                }
                return
            }

            db.pointTagDao().getPointTagByUID(hexId)?.let { pointTag ->
                runOnUiThread {
                    playScanFeedback()
                    navigateToNfcPointFragment(pointTag)
                }
                return
            }

            db.memberTagDao().getMemberTagByUID(hexId)?.let { memberTag ->
                runOnUiThread {
                    playScanFeedback()
                    navigateToNfcMemberFragment(memberTag)
                }
                return
            }

            runOnUiThread {
                playErrorFeedback()
                Toast.makeText(this, "Неизвестный чип", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigateToNfcPointFragment(checkpointTag: CheckpointTag) {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val bundle = Bundle().apply {
            putInt("checkpointTagId", checkpointTag.id)
        }
        navController.navigate(R.id.nfcPointFragment, bundle)
    }

    private fun navigateToNfcMemberFragment(memberTag: MemberTag) {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val bundle = Bundle().apply {
            putInt("memberTagId", memberTag.id)
        }
        navController.navigate(R.id.nfcPointFragment, bundle)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            println("NDEF_DISCOVERED")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag == null) {
                Toast.makeText(this, "tag is null", Toast.LENGTH_SHORT).show()
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                onTagDiscovered(tag)
            }
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
            .setMaxStreams(2)
            .setAudioAttributes(attributes)
            .build().apply {
                soundScan = load(this@MainActivity, R.raw.beep_send, 1)
                soundErr = load(this@MainActivity, R.raw.beep_err, 1)
            }
    }

    private fun playScanFeedback() {
        soundPool?.play(soundScan, 1f, 1f, 0, 0, 1f)
        buzz(success = true)
    }

    private fun playErrorFeedback() {
        soundPool?.play(soundErr, 1f, 1f, 0, 0, 1f)
        buzz(success = false)
    }

    private fun buzz(success: Boolean) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = if (success) VibrationEffect.DEFAULT_AMPLITUDE else 96
            vibrator.vibrate(VibrationEffect.createOneShot(40, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }
}
