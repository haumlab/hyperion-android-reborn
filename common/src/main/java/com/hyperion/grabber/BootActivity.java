package com.hyperion.grabber.common;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.hyperion.grabber.common.util.TclBypass;

public class BootActivity extends AppCompatActivity {
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final String TAG = "BootActivity";

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Try TCL bypass before requesting projection
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.i(TAG, "Restricted device detected: " + TclBypass.getDeviceInfo());
            TclBypass.tryShellBypass(this);
        }
        
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        } else {
            Log.e(TAG, "MediaProjectionManager is null");
            finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK) {
                startScreenRecorder(this, resultCode, data);
            } else {
                Log.w(TAG, "Media projection permission denied");
            }
            finish();
        }
    }

    public static void startScreenRecorder(Context context, int resultCode, Intent data) {
        // Try shell bypass before starting service on restricted devices
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            TclBypass.tryShellBypass(context);
            // Give bypass commands a moment to execute
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }
        
        Intent intent = new Intent(context, HyperionScreenService.class);
        intent.setAction(HyperionScreenService.ACTION_START);
        intent.putExtra(HyperionScreenService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtras(data);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage());
            // Fall back to regular startService which might work on some devices
            try {
                context.startService(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Fallback startService also failed: " + e2.getMessage());
            }
        }
    }
}
