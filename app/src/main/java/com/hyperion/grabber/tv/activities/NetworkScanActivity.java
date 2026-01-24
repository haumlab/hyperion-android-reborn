package com.hyperion.grabber.tv.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.hyperion.grabber.common.network.NetworkScanner;
import com.hyperion.grabber.common.util.HyperionScannerTask;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.R;


public class NetworkScanActivity extends LeanbackActivity implements HyperionScannerTask.Listener {
    private boolean isScanning = false;

    private Button startScanButton;
    private Button manualSetupButton;
    private ProgressBar progressBar;
    private TextView descriptionText;
    private Spinner languageSpinner;
    private boolean initialLanguageLoad = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_scan);
        
        startScanButton = findViewById(R.id.startScanButton);
        manualSetupButton = findViewById(R.id.manualSetupButton);
        progressBar = findViewById(R.id.progressBar);
        descriptionText = findViewById(R.id.scannerDescriptionText);
        languageSpinner = findViewById(R.id.languageSpinner);

        setupLanguageSpinner();
        
        // only if back was pressed on this Activity will we not be configured when we finish
        setResult(RESULT_OK);

    }

    private void setupLanguageSpinner() {
        String[] languages = {"English", "Russian", "German", "Spanish", "French", "Italian", "Dutch", "Norwegian", "Czech", "Arabic"};
        final String[] languageCodes = {"en", "ru", "de", "es", "fr", "it", "nl", "no", "cs", "ar"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        Preferences prefs = new Preferences(this);
        String currentLang = prefs.getLocale();
        for (int i = 0; i < languageCodes.length; i++) {
            if (languageCodes[i].equals(currentLang)) {
                languageSpinner.setSelection(i);
                break;
            }
        }

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initialLanguageLoad) {
                    initialLanguageLoad = false;
                    return;
                }
                String selectedLang = languageCodes[position];
                if (!selectedLang.equals(prefs.getLocale())) {
                    prefs.setLocale(selectedLang);
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultData, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.REQUEST_INITIAL_SETUP){
            if (resultCode == RESULT_OK){
                // when the setup completed successfully, we completed successfully.
                setResult(RESULT_OK);
                finish();
            }
        }
    }

    public void onClick(View v){
        if (v.getId() == R.id.startScanButton){
            if (!isScanning){
                new HyperionScannerTask(this).execute();
            }

        } else if (v.getId() == R.id.manualSetupButton){
            Intent intent = new Intent(this, ManualSetupActivity.class);
            startActivityForResult(intent, MainActivity.REQUEST_INITIAL_SETUP);
        }

    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public void onScannerProgress(float progress) {
        if (!isScanning){
            isScanning = true;
            startScanButton.setText(com.hyperion.grabber.common.R.string.scanner_scan_in_progress_button);
            descriptionText.setGravity(Gravity.CENTER);
            descriptionText.setText(getString(com.hyperion.grabber.common.R.string.scanner_scan_in_progress_text, "\uD83D\uDD75️")); // todo: 🕵️
        }

        progressBar.setProgress(Math.round(progress * 100));
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public void onScannerCompleted(@Nullable String foundIpAddress) {
        isScanning = false;

        if (foundIpAddress == null){
            startScanButton.setText(com.hyperion.grabber.common.R.string.scanner_retry_button);
            manualSetupButton.requestFocus();
            descriptionText.setText(getString(com.hyperion.grabber.common.R.string.scanner_no_results, "\uD83D\uDE29")); // 😩
        } else {
            Intent intent = new Intent(this, ScanResultActivity.class);
            intent.putExtra(ScanResultActivity.EXTRA_RESULT_HOST_NAME, foundIpAddress);

            intent.putExtra(ScanResultActivity.EXTRA_RESULT_PORT, String.valueOf(NetworkScanner.PORT));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish(); // Finish the current activity
        }
    }

}
