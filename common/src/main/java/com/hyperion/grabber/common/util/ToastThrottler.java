package com.hyperion.grabber.common.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class to prevent toast spam by throttling duplicate messages.
 * Android limits toast queues to 5 per package - this prevents hitting that limit.
 */
public class ToastThrottler {
    private static final long DEFAULT_THROTTLE_MS = 5000; // 5 seconds
    private static final Map<String, Long> sLastToastTimes = new HashMap<>();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Toast sCurrentToast = null;
    
    /**
     * Show a toast if the same message hasn't been shown recently.
     * @param context Application context
     * @param message Toast message
     * @param duration Toast duration (Toast.LENGTH_SHORT or Toast.LENGTH_LONG)
     */
    public static void showThrottled(Context context, String message, int duration) {
        showThrottled(context, message, duration, DEFAULT_THROTTLE_MS);
    }
    
    /**
     * Show a toast if the same message hasn't been shown recently.
     * @param context Application context
     * @param message Toast message
     * @param duration Toast duration
     * @param throttleMs Minimum time between same messages in milliseconds
     */
    public static void showThrottled(Context context, String message, int duration, long throttleMs) {
        if (message == null || context == null) return;
        
        long now = System.currentTimeMillis();
        String key = message.hashCode() + "_" + duration;
        
        synchronized (sLastToastTimes) {
            Long lastTime = sLastToastTimes.get(key);
            if (lastTime != null && (now - lastTime) < throttleMs) {
                // Throttled - don't show
                return;
            }
            sLastToastTimes.put(key, now);
        }
        
        // Cancel any existing toast to prevent queue buildup
        sHandler.post(() -> {
            try {
                if (sCurrentToast != null) {
                    sCurrentToast.cancel();
                }
                sCurrentToast = Toast.makeText(context.getApplicationContext(), message, duration);
                sCurrentToast.show();
            } catch (Exception e) {
                // Ignore toast failures
            }
        });
    }
    
    /**
     * Show a toast if the same resource message hasn't been shown recently.
     */
    public static void showThrottled(Context context, int messageResId, int duration) {
        try {
            String message = context.getString(messageResId);
            showThrottled(context, message, duration);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Clear throttle history (useful when activity is recreated)
     */
    public static void clearHistory() {
        synchronized (sLastToastTimes) {
            sLastToastTimes.clear();
        }
    }
    
    /**
     * Cancel any pending toast
     */
    public static void cancelCurrent() {
        sHandler.post(() -> {
            if (sCurrentToast != null) {
                sCurrentToast.cancel();
                sCurrentToast = null;
            }
        });
    }
}
