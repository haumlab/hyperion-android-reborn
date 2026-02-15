package com.hyperion.grabber;

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
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import com.hyperion.grabber.common.BootActivity;
import com.hyperion.grabber.common.HyperionActivityHelper;
import com.hyperion.grabber.common.HyperionScreenService;
import com.hyperion.grabber.common.util.PermissionHelper;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.common.util.TclBypass;
import com.hyperion.grabber.common.util.ToastThrottler;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ImageView.OnClickListener,
        ImageView.OnFocusChangeListener {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(HyperionActivityHelper.updateBaseContextLocale(newBase));
    }
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2;
    private static final int REQUEST_OVERLAY_PERMISSION = 3;
    private static final String TAG = "DEBUG";
    private boolean mRecorderRunning = false;
    private static MediaProjectionManager mMediaProjectionManager;
    private Intent mPendingProjectionData = null;
    private int mPendingResultCode = 0;
    private int mPermissionDeniedCount = 0;
    private boolean mTclWarningShown = false;
    private String mLastError = null;
    private Spinner languageSpinner;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean checked = intent.getBooleanExtra(HyperionScreenService.BROADCAST_TAG, false);
            mRecorderRunning = checked;
            String error = intent.getStringExtra(HyperionScreenService.BROADCAST_ERROR);
            boolean tclBlocked = intent.getBooleanExtra(HyperionScreenService.BROADCAST_TCL_BLOCKED, false);
            
            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true;
                TclBypass.showTclHelpDialog(MainActivity.this, () -> {
                    mTclWarningShown = false;
                    requestScreenCapture();
                });
            } else if (error != null && !error.equals(mLastError) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                            !HyperionGrabberTileService.isListening())) {
                mLastError = error;
                ToastThrottler.showThrottled(getBaseContext(), error, Toast.LENGTH_LONG);
            }
            setImageViews(checked, checked);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Check for updates
        checkForUpdates();
        mMediaProjectionManager = (MediaProjectionManager)
                                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ImageView iv = findViewById(R.id.power_toggle);
        iv.setOnClickListener(this);
        iv.setOnFocusChangeListener(this);
        iv.setFocusable(true);
        iv.requestFocus();

        View settingsButton = findViewById(R.id.settingsButton);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
            });
        }

        languageSpinner = findViewById(R.id.languageSpinner);
        if (languageSpinner != null) {
            HyperionActivityHelper.setupLanguageSpinner(this, languageSpinner);
        }

        setImageViews(mRecorderRunning, false);

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(HyperionScreenService.BROADCAST_FILTER));
        checkForInstance();
        
        // Request notification permission for Android 13+
        HyperionActivityHelper.requestNotificationPermission(this, REQUEST_NOTIFICATION_PERMISSION);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Check for updates when app returns to foreground
        checkForUpdates();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onClick(View view) {
        // Check for updates when user clicks play
        checkForUpdates();
        
        if (!mRecorderRunning) {
            requestScreenCapture();
        } else {
            stopScreenRecorder();
            mRecorderRunning = false;
        }
    }
    
    private void requestScreenCapture() {
        // On TCL and other restricted devices, try shell bypass first
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.d(TAG, "Detected TCL/restricted device, trying shell bypass");
            TclBypass.tryShellBypass(this);
        }
        
        // Also try general shell permissions
        PermissionHelper.tryGrantProjectMediaViaShell(this);

        // Start service with ACTION_PREPARE to satisfy foreground service requirement on Android 12+
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
        
        // Check overlay permission on first attempt
        if (mPermissionDeniedCount == 0 && !PermissionHelper.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission first");
            PermissionHelper.requestOverlayPermission(this, REQUEST_OVERLAY_PERMISSION);
            return;
        }
        
        try {
            // Give the system a tiny bit of time to register the foreground service
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) {
                    try {
                        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
                        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Screen capture permission denied: " + e.getMessage());
                        handlePermissionDenied();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to request screen capture: " + e.getMessage());
                        ToastThrottler.showThrottled(MainActivity.this, 
                                "Failed to request screen recording: " + e.getMessage(), Toast.LENGTH_LONG);
                        setImageViews(false, true);
                    }
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule capture request: " + e.getMessage());
        }
    }

    private void handlePermissionDenied() {
        mPermissionDeniedCount++;
        if (TclBypass.isTclDevice()) {
            TclBypass.showTclHelpDialog(this, this::requestScreenCapture);
        } else {
            PermissionHelper.showFullPermissionDialog(this, this::requestScreenCapture);
        }
        setImageViews(false, true);
    }

    @Override
    public void onFocusChange(View view, boolean focused) {
        if (focused) {
            ((ImageView) view).setColorFilter(Color.argb(255, 0, 0, 150));
        } else {
            ((ImageView) view).setColorFilter(Color.argb(255, 0, 0, 0));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                mPermissionDeniedCount++;
                mRecorderRunning = false;
                if (mPermissionDeniedCount >= 2) {
                    if (TclBypass.isTclDevice()) {
                        TclBypass.showTclHelpDialog(this, this::requestScreenCapture);
                    } else {
                        PermissionHelper.showFullPermissionDialog(this, this::requestScreenCapture);
                    }
                } else {
                    ToastThrottler.showThrottled(this, "Screen recording permission was denied. Tap again to retry.", Toast.LENGTH_LONG);
                }
                setImageViews(false, true);
                return;
            }
            mPermissionDeniedCount = 0;
            mTclWarningShown = false;
            Log.i(TAG, "Starting screen capture");
            startScreenRecorder(resultCode, (Intent) data.clone());
            mRecorderRunning = true;
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // Small delay before requesting capture - helps on some devices
            getWindow().getDecorView().postDelayed(this::requestScreenCapture, 500);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkForInstance() {
        if (HyperionActivityHelper.isServiceRunning(this)) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.GET_STATUS);
            startService(intent);
        }
    }

    public void startScreenRecorder(int resultCode, Intent data) {
        HyperionActivityHelper.startScreenRecorder(this, resultCode, data);
    }

    public void stopScreenRecorder() {
        HyperionActivityHelper.stopScreenRecorder(this, mRecorderRunning);
    }

    private void setImageViews(boolean running, boolean animated) {
        View rainbow = findViewById(R.id.sweepGradientView);
        View message = findViewById(R.id.grabberStartedText);
        View buttonImage = findViewById(R.id.power_toggle);
        if (running) {
            if (animated){
                HyperionActivityHelper.fadeView(rainbow, true);
                HyperionActivityHelper.fadeView(message, true);
            } else {
                rainbow.setVisibility(View.VISIBLE);
                message.setVisibility(View.VISIBLE);
            }
            buttonImage.setAlpha((float) 1);
        } else {
            if (animated){
                HyperionActivityHelper.fadeView(rainbow, false);
                HyperionActivityHelper.fadeView(message, false);
            } else {
                rainbow.setVisibility(View.INVISIBLE);
                message.setVisibility(View.INVISIBLE);
            }
            buttonImage.setAlpha((float) 0.25);
        }
    }

    private void checkForUpdates() {
        new Thread(() -> {
            try {
                Log.d(TAG, "Checking for updates...");
                
                com.hyperion.grabber.common.util.UpdateChecker checker = 
                    new com.hyperion.grabber.common.util.UpdateChecker(this);
                com.hyperion.grabber.common.util.GithubRelease release = checker.checkForUpdates();
                
                if (release != null) {
                    Log.d(TAG, "Update found: " + release.getTagName());
                    runOnUiThread(() -> showUpdateDialog(release));
                } else {
                    Log.d(TAG, "No updates available");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking for updates", e);
            }
        }).start();
    }
    
    private void showUpdateDialog(com.hyperion.grabber.common.util.GithubRelease release) {
        UpdateDialog dialog = new UpdateDialog(this);
        dialog.show(release, 
            () -> {
                com.hyperion.grabber.common.util.UpdateManager manager = 
                    new com.hyperion.grabber.common.util.UpdateManager(getApplicationContext());
                manager.downloadAndInstall(release.getDownloadUrl(), release.getTagName(), success -> {
                    return kotlin.Unit.INSTANCE;
                });
                return kotlin.Unit.INSTANCE;
            }, 
            () -> kotlin.Unit.INSTANCE
        );
    }
    
}
