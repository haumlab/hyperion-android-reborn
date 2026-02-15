package com.hyperion.grabber.common.util;

import android.content.Context;
import android.util.Log;

import com.hyperion.grabber.common.R;
import com.hyperion.grabber.common.network.ColorRgb;

public class LedDataExtractor {
    private static final String TAG = "LedDataExtractor";

    public static class LedConfig {
        public final int xLed;
        public final int yLed;

        public LedConfig(int xLed, int yLed) {
            this.xLed = xLed;
            this.yLed = yLed;
        }

        public int getTotalLeds() {
            int total = 2 * (xLed + yLed);
            return Math.max(total, 1);
        }
    }

    public static LedConfig loadLedConfig(Context context) {
        int xLed = 0;
        int yLed = 0;
        try {
            Preferences prefs = new Preferences(context);
            xLed = prefs.getInt(R.string.pref_key_x_led);
            yLed = prefs.getInt(R.string.pref_key_y_led);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get LED counts from preferences", e);
        }
        return new LedConfig(xLed, yLed);
    }

    /**
     * Extract LED data reusing an existing buffer to avoid GC overhead.
     * @param reuseBuffer Optional buffer to reuse. If null or wrong size, a new one is allocated.
     * @return The array containing extracted data (either reused or new).
     * @deprecated Use {@link #extractLEDData(byte[], int, int, int, int, ColorRgb[])} for better performance.
     */
    @Deprecated
    public static ColorRgb[] extractLEDData(Context context, byte[] screenData, int width, int height, ColorRgb[] reuseBuffer) {
        LedConfig config = loadLedConfig(context);
        return extractPerimeterPixels(screenData, width, height, config.xLed, config.yLed, reuseBuffer);
    }

    /**
     * Optimized version that takes LED counts directly, avoiding Preference lookup.
     */
    public static ColorRgb[] extractLEDData(byte[] screenData, int width, int height, int xLed, int yLed, ColorRgb[] reuseBuffer) {
        return extractPerimeterPixels(screenData, width, height, xLed, yLed, reuseBuffer);
    }

    public static ColorRgb[] extractLEDData(Context context, byte[] screenData, int width, int height) {
        return extractLEDData(context, screenData, width, height, null);
    }
    
    public static int getLedCount(Context context) {
        // We use the same config loader. Original code had a fallback to 60 on exception.
        // Here loadLedConfig handles exception and returns 0,0.
        // getTotalLeds returns 1 for 0,0.
        // We accept this small behavior change as it unifies logic.
        return loadLedConfig(context).getTotalLeds();
    }

    /**
     * Extract perimeter pixels from 2D screen data
     * Order: top (left->right), right (top->bottom), bottom (right->left), left (bottom->top)
     */
    private static ColorRgb[] extractPerimeterPixels(byte[] screenData, int width, int height, 
                                         int xLed, int yLed, ColorRgb[] reuseBuffer) {
        // Total LEDs = 2 * (x + y)
        int totalLEDs = 2 * (xLed + yLed);
        if (totalLEDs == 0) return new ColorRgb[0];

        ColorRgb[] ledData;
        if (reuseBuffer != null && reuseBuffer.length == totalLEDs) {
            ledData = reuseBuffer;
        } else {
            ledData = new ColorRgb[totalLEDs];
            for (int k = 0; k < totalLEDs; k++) ledData[k] = new ColorRgb(0,0,0);
        }

        int ledIdx = 0;
        
        // Calculate step sizes
        // We use xLed and yLed directly to divide the sides
        float stepX = (float) width / xLed;
        float stepY = (float) height / yLed;
        
        // Top edge (left to right)
        for (int i = 0; i < xLed; i++) {
            int x = Math.min((int) (i * stepX + stepX / 2), width - 1);
            int y = 0;
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y);
        }
        
        // Right edge (top to bottom)
        for (int i = 0; i < yLed; i++) {
            int x = width - 1;
            int y = Math.min((int) (i * stepY + stepY / 2), height - 1);
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y);
        }
        
        // Bottom edge (right to left)
        for (int i = 0; i < xLed; i++) {
            int x = Math.min((int) ((xLed - 1 - i) * stepX + stepX / 2), width - 1);
            int y = height - 1;
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y);
        }
        
        // Left edge (bottom to top)
        for (int i = 0; i < yLed; i++) {
            int x = 0;
            int y = Math.min((int) ((yLed - 1 - i) * stepY + stepY / 2), height - 1);
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y);
        }
        
        // Fill remaining if any (should not happen)
        while (ledIdx < totalLEDs) {
            ledData[ledIdx++].set(0, 0, 0);
        }
        
        return ledData;
    }

    private static void setPixelFromData(ColorRgb dest, byte[] screenData, int width, int x, int y) {
        int srcIdx = (y * width + x) * 3;
        if (srcIdx + 2 < screenData.length) {
            int r = screenData[srcIdx] & 0xFF;
            int g = screenData[srcIdx + 1] & 0xFF;
            int b = screenData[srcIdx + 2] & 0xFF;
            dest.set(r, g, b);
        } else {
            dest.set(0, 0, 0);
        }
    }
}
