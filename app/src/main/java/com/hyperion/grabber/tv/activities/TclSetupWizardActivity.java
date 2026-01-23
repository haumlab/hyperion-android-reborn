package com.hyperion.grabber.tv.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.hyperion.grabber.R;
import com.hyperion.grabber.common.util.AdbSelfPermission;
import com.hyperion.grabber.common.util.TclBypass;
import com.hyperion.grabber.common.util.ToastThrottler;

/**
 * A wizard-style activity that guides TCL/Google TV users through
 * enabling ADB and granting permissions to the app.
 */
public class TclSetupWizardActivity extends AppCompatActivity {
    
    private static final String TAG = "TclSetupWizard";
    public static final String EXTRA_RETURN_RESULT = "return_result";
    
    private int currentStep = 1;
    private static final int TOTAL_STEPS = 4;
    
    // Views
    private View[] stepDots;
    private View[] stepContents;
    private Button btnBack, btnNext, btnFinish, btnSkip;
    private TextView deviceInfoText;
    private ProgressBar adbCheckProgress, permissionProgress;
    private ImageView adbCheckIcon, permissionCheckIcon;
    private TextView adbStatusText, permissionStatusText;
    private LinearLayout authorizationHint;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private AdbSelfPermission adbPermission;
    private boolean permissionsGranted = false;
    
    /**
     * Launch the wizard activity
     */
    public static void launch(Context context) {
        Intent intent = new Intent(context, TclSetupWizardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    /**
     * Launch for result
     */
    public static Intent createIntent(Context context) {
        Intent intent = new Intent(context, TclSetupWizardActivity.class);
        intent.putExtra(EXTRA_RETURN_RESULT, true);
        return intent;
    }
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tcl_setup_wizard);
        
        adbPermission = new AdbSelfPermission(this);
        
        initViews();
        updateStep();
        showDeviceInfo();
    }
    
    private void initViews() {
        // Step dots
        stepDots = new View[] {
            findViewById(R.id.step1Dot),
            findViewById(R.id.step2Dot),
            findViewById(R.id.step3Dot),
            findViewById(R.id.step4Dot)
        };
        
        // Step contents
        stepContents = new View[] {
            findViewById(R.id.step1Content),
            findViewById(R.id.step2Content),
            findViewById(R.id.step3Content),
            findViewById(R.id.step4Content)
        };
        
        // Navigation buttons
        btnBack = findViewById(R.id.btnBack);
        btnNext = findViewById(R.id.btnNext);
        btnFinish = findViewById(R.id.btnFinish);
        btnSkip = findViewById(R.id.btnSkip);
        
        // Device info
        deviceInfoText = findViewById(R.id.deviceInfoText);
        
        // Step 3 views
        adbCheckProgress = findViewById(R.id.adbCheckProgress);
        adbCheckIcon = findViewById(R.id.adbCheckIcon);
        adbStatusText = findViewById(R.id.adbStatusText);
        
        // Step 4 views
        permissionProgress = findViewById(R.id.permissionProgress);
        permissionCheckIcon = findViewById(R.id.permissionCheckIcon);
        permissionStatusText = findViewById(R.id.permissionStatusText);
        authorizationHint = findViewById(R.id.authorizationHint);
        
        // Button click listeners
        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());
        btnFinish.setOnClickListener(v -> finish());
        btnSkip.setOnClickListener(v -> skipWizard());
        
        // Step 2 button - open device info
        Button btnOpenDeviceInfo = findViewById(R.id.btnOpenDeviceInfo);
        btnOpenDeviceInfo.setOnClickListener(v -> openDeviceInfo());
        
        // Step 3 button - open developer options
        Button btnOpenDeveloperOptions = findViewById(R.id.btnOpenDeveloperOptions);
        btnOpenDeveloperOptions.setOnClickListener(v -> openDeveloperOptions());
        
        // Step 4 button - grant permissions
        Button btnGrantPermissions = findViewById(R.id.btnGrantPermissions);
        btnGrantPermissions.setOnClickListener(v -> grantPermissions());
    }
    
    private void showDeviceInfo() {
        String info = "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                      "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                      "Brand: " + Build.BRAND + "\n" +
                      "Device: " + Build.DEVICE;
        deviceInfoText.setText(info);
    }
    
    private void updateStep() {
        // Update step pills - active/complete/inactive
        for (int i = 0; i < stepDots.length; i++) {
            int drawable;
            if (i < currentStep - 1) {
                drawable = R.drawable.step_pill_complete;  // Completed steps
            } else if (i == currentStep - 1) {
                drawable = R.drawable.step_pill_active;    // Current step
            } else {
                drawable = R.drawable.step_pill_inactive;  // Future steps
            }
            stepDots[i].setBackgroundResource(drawable);
        }
        
        // Update step content visibility
        for (int i = 0; i < stepContents.length; i++) {
            stepContents[i].setVisibility(i == currentStep - 1 ? View.VISIBLE : View.GONE);
        }
        
        // Update navigation buttons
        btnBack.setVisibility(currentStep > 1 ? View.VISIBLE : View.GONE);
        
        if (permissionsGranted) {
            btnNext.setVisibility(View.GONE);
            btnFinish.setVisibility(View.VISIBLE);
            btnSkip.setVisibility(View.GONE);
            findViewById(R.id.successContent).setVisibility(View.VISIBLE);
            for (View content : stepContents) {
                content.setVisibility(View.GONE);
            }
        } else {
            btnNext.setVisibility(currentStep < TOTAL_STEPS ? View.VISIBLE : View.GONE);
            btnFinish.setVisibility(View.GONE);
            btnSkip.setVisibility(View.VISIBLE);
        }
        
        // Perform step-specific actions
        if (currentStep == 3) {
            checkAdbStatus();
        }
    }
    
    private void goBack() {
        if (currentStep > 1) {
            currentStep--;
            updateStep();
        }
    }
    
    private void goNext() {
        if (currentStep < TOTAL_STEPS) {
            currentStep++;
            updateStep();
        }
    }
    
    private void skipWizard() {
        // Try shell bypass as fallback
        TclBypass.tryShellBypass(this);
        
        setResult(RESULT_CANCELED);
        finish();
    }
    
    private void openDeviceInfo() {
        Intent[] intents = {
            // Google TV / Android TV
            new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.device.DeviceInfoSettingsActivity")),
            new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.MainSettings")),
            // Generic settings
            new Intent(Settings.ACTION_SETTINGS),
        };
        
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        
        ToastThrottler.showThrottled(this, "Please go to Settings > Device Preferences > About manually", android.widget.Toast.LENGTH_LONG);
    }
    
    private void openDeveloperOptions() {
        Intent[] intents = {
            // Developer options
            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.system.development.DevelopmentSettingsActivity")),
            new Intent().setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.DevelopmentSettings")),
            // Fallback to main settings
            new Intent(Settings.ACTION_SETTINGS),
        };
        
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        
        ToastThrottler.showThrottled(this, "Please go to Settings > Device Preferences > Developer options manually", android.widget.Toast.LENGTH_LONG);
    }
    
    private void checkAdbStatus() {
        adbCheckProgress.setVisibility(View.VISIBLE);
        adbCheckIcon.setVisibility(View.GONE);
        adbStatusText.setText(R.string.tcl_checking_adb);
        
        new Thread(() -> {
            boolean enabled = AdbSelfPermission.isAdbEnabled();
            
            handler.post(() -> {
                adbCheckProgress.setVisibility(View.GONE);
                adbCheckIcon.setVisibility(View.VISIBLE);
                
                if (enabled) {
                    adbCheckIcon.setImageResource(R.drawable.ic_check_circle);
                    adbCheckIcon.setColorFilter(ContextCompat.getColor(this, R.color.colorAccent));
                    adbStatusText.setText(R.string.tcl_adb_enabled);
                } else {
                    adbCheckIcon.setImageResource(R.drawable.ic_error_circle);
                    adbCheckIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_light));
                    adbStatusText.setText(R.string.tcl_adb_disabled);
                }
            });
        }).start();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Recheck ADB status when returning from settings
        if (currentStep == 3) {
            handler.postDelayed(this::checkAdbStatus, 500);
        }
    }
    
    private void grantPermissions() {
        permissionProgress.setVisibility(View.VISIBLE);
        permissionCheckIcon.setVisibility(View.GONE);
        permissionStatusText.setText("Connecting...");
        authorizationHint.setVisibility(View.GONE);
        
        adbPermission.grantAllPermissions(new AdbSelfPermission.PermissionCallback() {
            @Override
            public void onSuccess(String message) {
                permissionProgress.setVisibility(View.GONE);
                permissionCheckIcon.setVisibility(View.VISIBLE);
                permissionCheckIcon.setImageResource(R.drawable.ic_check_circle);
                permissionCheckIcon.setColorFilter(ContextCompat.getColor(TclSetupWizardActivity.this, R.color.colorAccent));
                permissionStatusText.setText(message);
                
                permissionsGranted = true;
                
                // Show success after a moment
                handler.postDelayed(() -> updateStep(), 1500);
            }
            
            @Override
            public void onError(String error) {
                permissionProgress.setVisibility(View.GONE);
                permissionCheckIcon.setVisibility(View.VISIBLE);
                permissionCheckIcon.setImageResource(R.drawable.ic_error_circle);
                permissionCheckIcon.setColorFilter(ContextCompat.getColor(TclSetupWizardActivity.this, android.R.color.holo_red_light));
                permissionStatusText.setText("Error: " + error);
            }
            
            @Override
            public void onProgress(String status) {
                permissionStatusText.setText(status);
            }
            
            @Override
            public void onAdbNotEnabled() {
                permissionProgress.setVisibility(View.GONE);
                permissionCheckIcon.setVisibility(View.VISIBLE);
                permissionCheckIcon.setImageResource(R.drawable.ic_error_circle);
                permissionCheckIcon.setColorFilter(ContextCompat.getColor(TclSetupWizardActivity.this, android.R.color.holo_red_light));
                permissionStatusText.setText("ADB is not enabled. Please go back and complete Step 2.");
            }
            
            @Override
            public void onAuthorizationRequired() {
                permissionProgress.setVisibility(View.GONE);
                permissionCheckIcon.setVisibility(View.VISIBLE);
                permissionCheckIcon.setImageResource(R.drawable.ic_error_circle);
                permissionCheckIcon.setColorFilter(ContextCompat.getColor(TclSetupWizardActivity.this, android.R.color.holo_orange_light));
                permissionStatusText.setText("Authorization required - please accept the dialog and try again");
                authorizationHint.setVisibility(View.VISIBLE);
            }
        });
    }
    
    @Override
    public void finish() {
        if (permissionsGranted) {
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        super.finish();
    }
}
