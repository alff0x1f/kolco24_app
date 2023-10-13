package ru.kolco24.kolco24;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import ru.kolco24.kolco24.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home, R.id.navigation_taken_points, R.id.navigation_legends)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_activity_main);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.navView, navController);

        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            DataDownloader dataDownloader = new DataDownloader(getApplication());
            dataDownloader.hideToasts();
            dataDownloader.setLocalDownload(true);
            dataDownloader.downloadPoints();
            dataDownloader.downloadTeams(null);
        });


        // Check for available NFC Adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            // Check if NFC is enabled
            if (!nfcAdapter.isEnabled()) {
                // NFC is disabled; show a dialog or message to prompt the user to enable it
                showNFCEnableDialog();
                return;
            }
            handleIntent(getIntent());
        } else {
            // NFC is not supported; show a message and disable the NFC features
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_SHORT).show();
        }
    }

    private void showNFCEnableDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("NFC отключен");
        builder.setMessage("Пожалуйста, включите NFC в настройках вашего телефона");
        builder.setPositiveButton("Открыть настройки", (dialog, which) -> {
            // Open device settings to enable NFC
            Intent settingsIntent = new Intent(Settings.ACTION_NFC_SETTINGS);
            startActivity(settingsIntent);
        });
        builder.setNegativeButton("Отмена", (dialog, which) -> {
            // Handle cancel or provide an alternative action
            // show a message or dialog that NFC is required to proceed
            Toast.makeText(MainActivity.this, "Сканирование меток недоступно", Toast.LENGTH_SHORT).show();
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                byte[] tagId = tag.getId();
                // Convert the byte array to a hex string
                String hexId = byteArrayToHexString(tagId);
                // Display the ID
                Toast.makeText(this, "NFC Tag ID: " + hexId, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String byteArrayToHexString(byte[] byteArray) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : byteArray) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}