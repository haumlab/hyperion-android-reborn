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
    
    // Cache for detection results
    private static Boolean sCachedIsTcl = null;
    private static Boolean sCachedIsRestricted = null;
    
    public static boolean isTclDevice() {
        if (sCachedIsTcl != null) return sCachedIsTcl;
        
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        String device = Build.DEVICE.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String product = Build.PRODUCT.toLowerCase();
        
        sCachedIsTcl = manufacturer.contains("tcl") || 
               brand.contains("tcl") || 
               device.startsWith("g10") ||
               product.startsWith("g10") ||
               model.contains("tcl") ||
               model.contains("smart tv") ||
               // Check for TCL TV model patterns
               model.matches(".*\\d{2}[a-z]\\d{3}.*") ||  // TCL TV model pattern like 55S546
               product.contains("google_atv");  // Google TV on TCL
        
        return sCachedIsTcl;
    }
    
    public static boolean isRestrictedManufacturer() {
        if (sCachedIsRestricted != null) return sCachedIsRestricted;
        
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        
        sCachedIsRestricted = manufacturer.contains("tcl") || 
               brand.contains("tcl") ||
               manufacturer.contains("xiaomi") || 
               manufacturer.contains("huawei") ||
               manufacturer.contains("oppo") ||
               manufacturer.contains("vivo") ||
               manufacturer.contains("realme") ||
               manufacturer.contains("samsung") ||
               manufacturer.contains("hisense") ||
               manufacturer.contains("skyworth");
               
        return sCachedIsRestricted;
    }
    
    /**
     * Returns device info string for debugging
     */
    public static String getDeviceInfo() {
        return "Manufacturer: " + Build.MANUFACTURER + 
               ", Brand: " + Build.BRAND + 
               ", Device: " + Build.DEVICE + 
               ", Model: " + Build.MODEL +
               ", Product: " + Build.PRODUCT;
    }
    
    /**
     * Check if ADB over network is enabled on this device
     */
    public static boolean isAdbNetworkEnabled() {
        return AdbSelfPermission.isAdbEnabled();
    }
    
    /**
     * Try to grant permissions using ADB self-connection
     * This is the preferred method when ADB is enabled
     */
    public static void tryAdbSelfGrant(Context context, AdbSelfPermission.PermissionCallback callback) {
        AdbSelfPermission adb = new AdbSelfPermission(context);
        adb.grantAllPermissions(callback);
    }
    
    /**
     * Check if the device needs TCL setup wizard
     */
    public static boolean needsSetupWizard(Context context) {
        // Only show wizard for restricted manufacturers where ADB might help
        if (!isTclDevice() && !isRestrictedManufacturer()) {
            return false;
        }
        
        // Check if we can already start foreground services
        // This is a heuristic - if shell bypass worked before, we might not need wizard
        return true;
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
            int uid = android.os.Process.myUid();
            
            // Commands sorted by priority - most likely to work first
            String[] commands = {
                // Foreground service permissions (critical for TCL)
                "appops set " + pkg + " START_FOREGROUND allow",
                "appops set " + pkg + " INSTANT_APP_START_FOREGROUND allow",
                "appops set --uid " + uid + " START_FOREGROUND allow",
                
                // Media projection permissions
                "appops set " + pkg + " PROJECT_MEDIA allow",
                "appops set " + pkg + " android:project_media allow",
                "appops set --uid " + uid + " PROJECT_MEDIA allow",
                
                // Overlay permission
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                
                // TCL-specific auto-start settings
                "settings put global auto_start_" + pkg + " 1",
                "settings put secure auto_start_" + pkg + " 1",
                "settings put global tcl_app_boot_" + pkg + " allow",
                "settings put secure tcl_app_boot_" + pkg + " allow",
                "settings put global tcl_forbid_autostart_" + pkg + " 0",
                
                // TCL broadcast to allow auto-start
                "am broadcast -a com.tcl.action.ALLOW_AUTO_START --es package " + pkg,
                "am broadcast -a com.tcl.appboot.action.SET_ALLOW --es package " + pkg,
                "am broadcast -a com.tcl.guard.action.AUTOSTART --ez allow true --es package " + pkg,
                
                // Battery optimization exemption
                "cmd deviceidle whitelist +" + pkg,
                "dumpsys deviceidle whitelist +" + pkg,
                
                // Background execution permissions
                "appops set " + pkg + " RUN_IN_BACKGROUND allow",
                "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
            };
            
            int successCount = 0;
            for (String cmd : commands) {
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    process.waitFor();
                    int exit = process.exitValue();
                    if (exit == 0) {
                        Log.d(TAG, "Success: " + cmd);
                        successCount++;
                    }
                } catch (Exception e) {
                    // Silently ignore - these require elevated permissions
                }
            }
            
            if (successCount > 0) {
                Log.i(TAG, "Shell bypass: " + successCount + " commands succeeded");
            }
        }).start();
    }
    
    public static void showTclHelpDialog(Activity activity, Runnable onRetry) {
        String pkg = activity.getPackageName();
        
        String message = "Your TV (TCL/Google TV) is blocking the screen recording service.\n\n" +
                "SOLUTION - Run these ADB commands from a computer:\n\n" +
                "1. Connect computer to same network as TV\n" +
                "2. Enable ADB in TV Developer Options\n" +
                "3. Run commands:\n\n" +
                "adb connect <TV_IP_ADDRESS>\n\n" +
                "adb shell appops set " + pkg + " PROJECT_MEDIA allow\n\n" +
                "adb shell appops set " + pkg + " START_FOREGROUND allow\n\n" +
                "Or install with all permissions:\n" +
                "adb install -g -r hyperion-grabber.apk\n\n" +
                "After running commands, tap Retry.";
        
        new AlertDialog.Builder(activity)
            .setTitle("TCL/Google TV Blocked")
            .setMessage(message)
            .setPositiveButton("Retry", (d, w) -> {
                tryShellBypass(activity);
                if (onRetry != null) {
                    activity.getWindow().getDecorView().postDelayed(() -> onRetry.run(), 1500);
                }
            })
            .setNeutralButton("Open Settings", (d, w) -> {
                if (!openTclAutoStartSettings(activity)) {
                    openSpecialAppAccess(activity);
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
