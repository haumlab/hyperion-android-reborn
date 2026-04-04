package com.hyperion.grabber.common.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

public class TclBypass {
    private static final String TAG = "TclBypass";
    
    public static boolean isTclDevice() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        String device = Build.DEVICE.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        
        return manufacturer.contains("tcl") || 
               brand.contains("tcl") || 
               device.startsWith("g10") ||
               model.contains("tcl") ||
               model.contains("smart tv");
    }
    
    public static boolean isRestrictedManufacturer() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("tcl") || 
               manufacturer.contains("xiaomi") || 
               manufacturer.contains("huawei") ||
               manufacturer.contains("oppo") ||
               manufacturer.contains("vivo") ||
               manufacturer.contains("realme") ||
               manufacturer.contains("samsung");
    }
    
    public static boolean openTclAutoStartSettings(Context context) {
        Intent[] intents = {
            // TCL specific intents
            new Intent().setComponent(new ComponentName("com.tcl.guard", 
                    "com.tcl.guard.activity.AutostartActivity")),
            new Intent().setComponent(new ComponentName("com.tcl.guard", 
                    "com.tcl.guard.activity.AppAutoStartManagerActivity")),
            new Intent().setComponent(new ComponentName("com.android.settings", 
                    "com.tcl.settings.TclAutoStartSettingsActivity")),
            new Intent().setComponent(new ComponentName("com.tcl.tvweishi", 
                    "com.tcl.tvweishi.settings.AutoBootManageActivity")),
            // TCL TV Settings
            new Intent().setComponent(new ComponentName("com.android.tv.settings", 
                    "com.android.tv.settings.MainSettings")),
            new Intent().setComponent(new ComponentName("com.android.tv.settings", 
                    "com.android.tv.settings.device.apps.AppsActivity")),
            // Generic auto-start intents
            new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + context.getPackageName())),
        };
        
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (isIntentAvailable(context, intent)) {
                    context.startActivity(intent);
                    Log.d(TAG, "Opened: " + intent.getComponent());
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "Failed to open: " + intent, e);
            }
        }
        return false;
    }
    
    public static boolean openSpecialAppAccess(Context context) {
        Intent[] intents = {
            // Special app access
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:" + context.getPackageName())),
            new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION")
                    .setData(Uri.parse("package:" + context.getPackageName())),
            // TV Settings apps section
            new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.device.apps.AppManagementActivity"))
                    .putExtra("packageName", context.getPackageName()),
        };
        
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (isIntentAvailable(context, intent)) {
                    context.startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "Failed: " + intent, e);
            }
        }
        return false;
    }
    
    public static void tryShellBypass(Context context) {
        new Thread(() -> {
            String pkg = context.getPackageName();
            String[] commands = {
                "appops set " + pkg + " PROJECT_MEDIA allow",
                "appops set " + pkg + " android:project_media allow",
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "settings put global auto_start_" + pkg + " 1",
                "settings put secure auto_start_" + pkg + " 1",
                "am broadcast -a com.tcl.action.ALLOW_AUTO_START -e package " + pkg,
                "am broadcast -a com.tcl.appboot.action.SET_ALLOW -e package " + pkg,
                "settings put global tcl_app_boot_" + pkg + " allow",
                "settings put secure tcl_app_boot_" + pkg + " allow",
                "cmd deviceidle whitelist +" + pkg,
                "dumpsys deviceidle whitelist +" + pkg,
                "appops set " + pkg + " RUN_IN_BACKGROUND allow",
                "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                "appops set " + pkg + " START_FOREGROUND allow",
                "appops set " + pkg + " INSTANT_APP_START_FOREGROUND allow",
            };
            
            for (String cmd : commands) {
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    process.waitFor();
                    int exit = process.exitValue();
                    if (exit == 0) {
                        Log.d(TAG, "Success: " + cmd);
                    }
                } catch (Exception e) {
                }
            }
        }).start();
    }
    
    public static void showTclHelpDialog(Activity activity, Runnable onRetry) {
        String pkg = activity.getPackageName();
        
        String message = "Your TV is blocking the screen recording service.\n\n" +
                "USE ADB TO FIX (from computer):\n\n" +
                "adb shell appops set " + pkg + " PROJECT_MEDIA allow\n\n" +
                "adb shell appops set " + pkg + " START_FOREGROUND allow\n\n" +
                "Or reinstall with permissions:\n" +
                "adb install -g -r hyperion.apk";
        
        new AlertDialog.Builder(activity)
            .setTitle("TCL Blocked")
            .setMessage(message)
            .setPositiveButton("Retry", (d, w) -> {
                tryShellBypass(activity);
                if (onRetry != null) {
                    activity.getWindow().getDecorView().postDelayed(() -> onRetry.run(), 1000);
                }
            })
            .setNegativeButton("Close", null)
            .setCancelable(true)
            .show();
    }
    
    private static boolean isIntentAvailable(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return list != null && !list.isEmpty();
    }
}
