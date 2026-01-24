package com.hyperion.grabber.tv.activities;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatDelegate;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import com.hyperion.grabber.common.BootActivity;
import com.hyperion.grabber.common.util.ToastThrottler;
import com.hyperion.grabber.common.HyperionScreenService;
import com.hyperion.grabber.common.util.TclBypass;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.R;

public class MainActivity extends LeanbackActivity implements ImageView.OnClickListener,
        ImageView.OnFocusChangeListener {
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    public static final int REQUEST_INITIAL_SETUP = 2;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 3;
    private static final int REQUEST_TCL_SETUP = 4;
    public static final String BROADCAST_ERROR = "SERVICE_ERROR";
    public static final String BROADCAST_TAG = "SERVICE_STATUS";
    public static final String BROADCAST_FILTER = "SERVICE_FILTER";
    public static final String BROADCAST_TCL_BLOCKED = "TCL_BLOCKED";
    private static final String TAG = "DEBUG";
    private boolean mRecorderRunning = false;
    private static MediaProjectionManager mMediaProjectionManager;
    private int mPermissionDeniedCount = 0;
    private boolean mTclDialogShown = false;
    private boolean mTclSetupShown = false;
    private String mLastErrorShown = null;
    private Spinner languageSpinner;
    private boolean initialLanguageLoad = true;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean checked = intent.getBooleanExtra(BROADCAST_TAG, false);
            mRecorderRunning = checked;
            String error = intent.getStringExtra(BROADCAST_ERROR);
            boolean tclBlocked = intent.getBooleanExtra(BROADCAST_TCL_BLOCKED, false);
            
            if (tclBlocked && !mTclDialogShown && !mTclSetupShown) {
                mTclSetupShown = true;
                // Launch the TCL setup wizard
                startActivityForResult(
                    TclSetupWizardActivity.createIntent(MainActivity.this), 
                    REQUEST_TCL_SETUP
                );
            } else if (error != null && !error.equals(mLastErrorShown)) {
                mLastErrorShown = error;
                ToastThrottler.showThrottled(getBaseContext(), error, Toast.LENGTH_SHORT);
            }
            setImageViews(checked, true);
        }
    };

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!initIfConfigured()){
            startSetup();
        }
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }
    }
    
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ToastThrottler.showThrottled(this, "Notification permission is needed for the foreground service", Toast.LENGTH_LONG);
            }
        }
    }

    /** @return whether the activity was initialized */
    private boolean initIfConfigured() {
        // Do we have a valid server config?
        Preferences preferences = new Preferences(getApplicationContext());
        String host = preferences.getString(com.hyperion.grabber.common.R.string.pref_key_host, null);
        int port = preferences.getInt(com.hyperion.grabber.common.R.string.pref_key_port, -1);

        if (host == null || port == -1){
            return false;
        }

        initActivity();
        
        // Check if this is a TCL/restricted device and offer setup wizard proactively
        checkForTclSetup(preferences);
        
        return true;
    }
    
    private void checkForTclSetup(Preferences preferences) {
        // Only prompt once per installation
        android.content.SharedPreferences prefs = getSharedPreferences("hyperion_tcl", Context.MODE_PRIVATE);
        boolean alreadyPrompted = prefs.getBoolean("tcl_setup_prompted", false);
        
        if (!alreadyPrompted && TclBypass.isTclDevice()) {
            // Mark as prompted
            prefs.edit().putBoolean("tcl_setup_prompted", true).apply();
            
            // Show a dialog offering to run the setup wizard
            new android.app.AlertDialog.Builder(this)
                .setTitle("TCL TV Detected")
                .setMessage("Your TV may block screen recording by default. " +
                           "Would you like to run the setup wizard to grant the required permissions?")
                .setPositiveButton("Run Setup", (d, w) -> {
                    startActivityForResult(
                        TclSetupWizardActivity.createIntent(this),
                        REQUEST_TCL_SETUP
                    );
                })
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
        }
    }

    private void startSetup() {
        // Start onboarding (setup)
        Intent intent = new Intent(this, NetworkScanActivity.class);
        startActivityForResult(intent, REQUEST_INITIAL_SETUP);
    }

    // Prepare activity for display
    private void initActivity() {
        // assume the recorder is not running until we are notified otherwise
        mRecorderRunning = false;

        setContentView(R.layout.activity_main);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        mMediaProjectionManager = (MediaProjectionManager)
                                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ImageView iv = findViewById(R.id.power_toggle);
        iv.setOnClickListener(this);
        iv.setOnFocusChangeListener(this);
        iv.setFocusable(true);
        iv.requestFocus();

        ImageButton ib = findViewById(R.id.settingsButton);
        ib.setOnClickListener(this);
        ib.setOnFocusChangeListener(this);
        ib.setFocusable(true);

        languageSpinner = findViewById(R.id.languageSpinner);
        if (languageSpinner != null) {
            setupLanguageSpinner();
        }

        setImageViews(mRecorderRunning, false);

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(BROADCAST_FILTER));

        // request an update on the running status
        checkForInstance();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.power_toggle) {
            if (!mRecorderRunning) {
                requestScreenCapture();
            } else {
                stopScreenRecorder();
                mRecorderRunning = false;
            }
        } else if (id == R.id.settingsButton) {
            startSettings();
        }
    }
    
    private void requestScreenCapture() {
        TclBypass.tryShellBypass(this);
        
        // Start service with ACTION_PREPARE to satisfy foreground service requirement
        Intent prepareIntent = new Intent(this, HyperionScreenService.class);
        prepareIntent.setAction(HyperionScreenService.ACTION_PREPARE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(prepareIntent);
            } else {
                startService(prepareIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service for preparation: " + e.getMessage());
        }
        
        try {
            // Give the system a tiny bit of time to register the foreground service
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) {
                    try {
                        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
                        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to request screen capture: " + e.getMessage());
                        handleCaptureRequestError();
                    }
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule capture request: " + e.getMessage());
        }
    }

    private void handleCaptureRequestError() {
        mPermissionDeniedCount++;
        // Launch TCL setup wizard if on TCL and not already shown
        if (!mTclSetupShown && TclBypass.isTclDevice()) {
            mTclSetupShown = true;
            startActivityForResult(
                TclSetupWizardActivity.createIntent(this), 
                REQUEST_TCL_SETUP
            );
        } else {
            ToastThrottler.showThrottled(this, "Failed to request screen recording", Toast.LENGTH_LONG);
        }
    }

    @Override
    public void onFocusChange(View view, boolean focused) {
        int clr = Color.argb(255, 0, 0, 150);
        if (!focused) {
            clr = Color.argb(255, 0, 0, 0);
        }
        int id = view.getId();
        if (id == R.id.power_toggle) {
            ((ImageView) view).setColorFilter(clr);
        } else if (id == R.id.settingsButton) {
            ((ImageButton) view).setColorFilter(clr);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INITIAL_SETUP){
            if (resultCode == RESULT_OK){
                if (!initIfConfigured()){
                    startSetup();
                }
            } else {
                finish();
            }
            return;
        }
        if (requestCode == REQUEST_TCL_SETUP) {
            mTclSetupShown = false;
            if (resultCode == RESULT_OK) {
                // Permissions were granted, try again
                mPermissionDeniedCount = 0;
                requestScreenCapture();
            }
            return;
        }
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                mPermissionDeniedCount++;
                ToastThrottler.showThrottled(this, "Permission denied. Tap again to retry.", Toast.LENGTH_SHORT);
                if (mPermissionDeniedCount >= 2 && !mTclSetupShown) {
                    // Launch TCL setup wizard instead of dialog
                    mTclSetupShown = true;
                    startActivityForResult(
                        TclSetupWizardActivity.createIntent(this), 
                        REQUEST_TCL_SETUP
                    );
                }
                if (mRecorderRunning) {
                    stopScreenRecorder();
                }
                mRecorderRunning = false;
                setImageViews(false, true);
                return;
            }
            mPermissionDeniedCount = 0;
            Log.i(TAG, "Starting screen capture");
            startScreenRecorder(resultCode, (Intent) data.clone());
            mRecorderRunning = true;
        }
    }

    private void startSettings() {
        stopScreenRecorder();
        Intent intent = new Intent(this, ManualSetupActivity.class);
        Bundle bundle =
                ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                        .toBundle();
        startActivity(intent, bundle);
    }

    private void checkForInstance() {
        if (isServiceRunning()) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.GET_STATUS);
            startService(intent);
        }
    }

    private void setImageViews(boolean running, boolean animated) {
        View rainbow = findViewById(R.id.sweepGradientView);
        View message = findViewById(R.id.grabberStartedText);
        if (running) {
            if (animated){
                fadeView(rainbow, true);
                fadeView(message, true);
            } else {
                rainbow.setVisibility(View.VISIBLE);
                message.setVisibility(View.VISIBLE);
            }
        } else {
            if (animated){
                fadeView(rainbow, false);
                fadeView(message, false);
            } else {
                rainbow.setVisibility(View.INVISIBLE);
                message.setVisibility(View.INVISIBLE);
            }
        }
    }

    public void startScreenRecorder(int resultCode, Intent data) {
        BootActivity.startScreenRecorder(this, resultCode, data);
    }

    public void stopScreenRecorder() {
        if (mRecorderRunning) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.ACTION_EXIT);
            startService(intent);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        assert manager != null;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (HyperionScreenService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void fadeView(View view, boolean visible){
        float alpha = visible ? 1f : 0f;
        int endVisibility = visible ? View.VISIBLE : View.INVISIBLE;
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(alpha)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(endVisibility);
                    }
                })
                .start();


    }
}
