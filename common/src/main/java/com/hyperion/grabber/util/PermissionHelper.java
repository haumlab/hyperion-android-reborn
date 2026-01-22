package com.hyperion.grabber.common.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class PermissionHelper {
    private static final String TAG = "PermissionHelper";
    
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }
    
    public static void requestOverlayPermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, requestCode);
            } catch (Exception e) {
                Log.e(TAG, "Cannot request overlay permission", e);
                openAppSettings(activity);
            }
        }
    }
    
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true;
    }
    
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Cannot request battery optimization exemption", e);
            }
        }
    }
    
    public static boolean hasProjectMediaPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
                int mode = appOps.checkOpNoThrow("android:project_media", 
                        android.os.Process.myUid(), context.getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) {
                Log.w(TAG, "Cannot check PROJECT_MEDIA permission", e);
            }
        }
        return true;
    }
    
    public static void openAppSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open app settings", e);
            Toast.makeText(context, "Please open Settings > Apps > Hyperion Grabber manually", Toast.LENGTH_LONG).show();
        }
    }
    
    public static void tryGrantProjectMediaViaShell(Context context) {
        new Thread(() -> {
            try {
                String pkg = context.getPackageName();
                String[] commands = {
                    "appops set " + pkg + " PROJECT_MEDIA allow",
                    "appops set " + pkg + " android:project_media allow",
                    "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                    "pm grant " + pkg + " android.permission.SYSTEM_ALERT_WINDOW"
                };
                
                for (String cmd : commands) {
                    try {
                        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                        process.waitFor();
                        Log.d(TAG, "Executed: " + cmd + " (exit: " + process.exitValue() + ")");
                    } catch (Exception e) {
                        Log.w(TAG, "Command failed: " + cmd);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Shell commands failed", e);
            }
        }).start();
    }
    
    public static void showFullPermissionDialog(Activity activity, Runnable onRetry) {
        String message = "Screen recording permission could not be obtained.\n\n" +
                "Your TV may be blocking the permission dialog.\n\n" +
                "SOLUTIONS:\n\n" +
                "1. OVERLAY PERMISSION:\n" +
                "   Settings > Apps > Special access > Display over other apps\n\n" +
                "2. APP PERMISSIONS:\n" +
                "   Settings > Apps > Hyperion Grabber > Permissions\n\n" +
                "3. AUTO-START (TCL TVs):\n" +
                "   Settings > Privacy > Special app access > Auto-start\n\n" +
                "4. BATTERY OPTIMIZATION:\n" +
                "   Settings > Apps > Special access > Battery optimization\n\n" +
                "5. ADB COMMAND (requires computer):\n" +
                "   adb shell appops set " + activity.getPackageName() + " PROJECT_MEDIA allow";
        
        new AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Open App Settings", (d, w) -> openAppSettings(activity))
            .setNeutralButton("Try Overlay Permission", (d, w) -> {
                requestOverlayPermission(activity, 999);
            })
            .setNegativeButton("Retry", (d, w) -> {
                if (onRetry != null) onRetry.run();
            })
            .setCancelable(true)
            .show();
    }
    
    public static void requestAllPermissions(Activity activity) {
        tryGrantProjectMediaViaShell(activity);
        
        if (!isIgnoringBatteryOptimizations(activity)) {
            requestIgnoreBatteryOptimizations(activity);
        }
        
        if (!canDrawOverlays(activity)) {
            requestOverlayPermission(activity, 999);
        }
    }
}
