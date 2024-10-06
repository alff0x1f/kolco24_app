package ru.kolco24.kolco24

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.databinding.ActivityMainBinding
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(application)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.navigation_home, R.id.navigation_taken_points, R.id.navigation_legends
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

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val dataDownloader = DataDownloader(application)
            dataDownloader.hideToasts()
            dataDownloader.setLocalDownload(true)
            dataDownloader.downloadPoints()
            dataDownloader.downloadTeams(null)
        }

        // Check for available NFC Adapter
        if (nfcAdapter == null) {
            // NFC is not supported;
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_SHORT).show()
        } else {
            // Check if NFC is enabled
            if (!nfcAdapter.isEnabled) {
                // NFC is disabled; show a dialog or message to prompt the user to enable it
                showNFCEnableDialog()
                return
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    private fun enableNfcReaderMode() {
        println("Enabling NFC reader mode in MainActivity")
        if (::nfcAdapter.isInitialized) {
            nfcAdapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A,
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

    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            // Access the NDEF technology
            val ndef = Ndef.get(it)
            var pointNdafExist = false
            if (ndef != null) {
                // Connect to the tag
                ndef.connect()
                // Check if the tag has NDEF data
                val ndefMessage = ndef.cachedNdefMessage
                if (ndefMessage != null) {
                    val records = ndefMessage.records
                    for (record in records) {
                        val record_type = String(record.type, Charset.forName("US-ASCII"))
                        println("Record type: $record_type")
                        if (record_type == "kolco24/point") {
                            pointNdafExist = true
                        }
                    }
                } else {
                    println("No NDEF message found on the tag")
                }
                // Always close the connection when done
                ndef.close()
            } else {
                runOnUiThread({
                    Toast.makeText(this, "Метка не поддерживает NDEF", Toast.LENGTH_SHORT)
                        .show()
                })
            }
            if (pointNdafExist) {
                val hexId = bytesToHex(tag.id)
                val pointTag = db.pointTagDao().getPointTagByTag(hexId)
                if (pointTag != null) {
                    val pointNumber = db.pointDao().getPointById(pointTag.pointId).number
                    runOnUiThread {
                        navigateToNfcPointFragment(pointNumber)
                    }

                }
            } else {
                runOnUiThread({
                    Toast.makeText(this, "Метка не является меткой Кольцо24", Toast.LENGTH_SHORT)
                        .show()
                })
            }
        }
    }

    private fun navigateToNfcPointFragment(pointNumber: Int) {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val bundle = Bundle().apply {
            putInt("pointNumber", pointNumber)  // Pass pointNumber as an argument
        }
        navController.navigate(R.id.nfcPointFragment, bundle)
        println("Navigating to NfcPointFragment with pointNumber: $pointNumber")
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
