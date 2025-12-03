package com.hyperion.grabber.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Helper class for Shizuku integration.
 * 
 * Shizuku allows apps to use ADB-level (shell) permissions without root.
 * This enables SurfaceControl screen capture which normally requires system permissions.
 * 
 * Setup for users:
 * 1. Install Shizuku app from Play Store or GitHub
 * 2. Start Shizuku via ADB: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
 * 3. Grant permission to Hyperion when prompted
 * 4. SurfaceControl capture will now work!
 * 
 * Note: Shizuku needs to be restarted after device reboot.
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    
    private static boolean sShizukuPermissionGranted = false;
    private static boolean sShizukuAvailable = false;
    private static boolean sInitialized = false;
    
    // Permission request code
    public static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;
    
    /**
     * Listener for Shizuku permission results
     */
    public interface ShizukuPermissionListener {
        void onPermissionGranted();
        void onPermissionDenied();
        void onShizukuNotRunning();
    }
    
    private static ShizukuPermissionListener sPermissionListener;
    
    // Shizuku listeners
    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        Log.i(TAG, "Shizuku binder received");
        sShizukuAvailable = true;
        checkPermission();
    };
    
    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        Log.i(TAG, "Shizuku binder dead");
        sShizukuAvailable = false;
        sShizukuPermissionGranted = false;
    };
    
    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT_LISTENER = 
            (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            sShizukuPermissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED);
            Log.i(TAG, "Shizuku permission result: " + (sShizukuPermissionGranted ? "granted" : "denied"));
            
            if (sPermissionListener != null) {
                if (sShizukuPermissionGranted) {
                    sPermissionListener.onPermissionGranted();
                } else {
                    sPermissionListener.onPermissionDenied();
                }
            }
        }
    };
    
    /**
     * Initialize Shizuku integration.
     * Call this in Application.onCreate() or Activity.onCreate()
     */
    public static synchronized void initialize() {
        if (sInitialized) return;
        sInitialized = true;
        
        Log.i(TAG, "Initializing Shizuku helper...");
        
        try {
            Shizuku.addBinderReceivedListener(BINDER_RECEIVED_LISTENER);
            Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
            Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
            
            // Check if Shizuku is already running
            if (Shizuku.pingBinder()) {
                sShizukuAvailable = true;
                checkPermission();
            }
            
            Log.i(TAG, "Shizuku available: " + sShizukuAvailable);
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize Shizuku: " + e.getMessage());
        }
    }
    
    /**
     * Cleanup Shizuku listeners.
     * Call this when the app is being destroyed.
     */
    public static void cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
            Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER);
            Shizuku.removeRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up Shizuku: " + e.getMessage());
        }
        sInitialized = false;
    }
    
    /**
     * Check if Shizuku is available and running.
     */
    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if we have Shizuku permission.
     */
    public static boolean hasShizukuPermission() {
        if (!isShizukuAvailable()) return false;
        
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Request Shizuku permission.
     */
    public static void requestPermission(ShizukuPermissionListener listener) {
        sPermissionListener = listener;
        
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku not running");
            if (listener != null) {
                listener.onShizukuNotRunning();
            }
            return;
        }
        
        if (hasShizukuPermission()) {
            sShizukuPermissionGranted = true;
            if (listener != null) {
                listener.onPermissionGranted();
            }
            return;
        }
        
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to request Shizuku permission: " + e.getMessage());
            if (listener != null) {
                listener.onPermissionDenied();
            }
        }
    }
    
    private static void checkPermission() {
        try {
            sShizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Shizuku permission: " + (sShizukuPermissionGranted ? "granted" : "not granted"));
        } catch (Exception e) {
            sShizukuPermissionGranted = false;
        }
    }
    
    /**
     * Check if SurfaceControl capture is possible via Shizuku.
     */
    public static boolean canUseSurfaceControl() {
        return isShizukuAvailable() && hasShizukuPermission();
    }
    
    /**
     * Get a system service binder via Shizuku.
     * This allows accessing hidden system APIs with shell permissions.
     */
    public static IBinder getSystemService(String serviceName) {
        if (!canUseSurfaceControl()) {
            Log.w(TAG, "Cannot get system service - Shizuku not ready");
            return null;
        }
        
        try {
            IBinder binder = SystemServiceHelper.getSystemService(serviceName);
            if (binder != null) {
                return new ShizukuBinderWrapper(binder);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get system service " + serviceName + ": " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Execute a command with shell permissions via Shizuku.
     * Note: This requires using Shizuku's UserService for proper command execution.
     * For now, this is a placeholder - SurfaceControl access is handled via reflection.
     */
    public static String executeCommand(String command) {
        // Shizuku.newProcess is not available in the public API
        // Commands should be executed via UserService or direct reflection
        Log.w(TAG, "executeCommand not implemented - use reflection APIs instead");
        return null;
    }
    
    /**
     * Get Shizuku status string for display.
     */
    public static String getStatusString() {
        if (!sInitialized) {
            return "Shizuku: Not initialized";
        }
        
        if (!isShizukuAvailable()) {
            return "Shizuku: Not running\n(Install Shizuku app and start via ADB)";
        }
        
        if (!hasShizukuPermission()) {
            return "Shizuku: Running but no permission\n(Grant permission in Shizuku app)";
        }
        
        return "Shizuku: Ready ✓";
    }
    
    /**
     * Get version of Shizuku if available
     */
    public static int getShizukuVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Exception e) {
            return -1;
        }
    }
}
