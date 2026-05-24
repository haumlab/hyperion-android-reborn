package com.hyperion.grabber.common.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Performance optimizer utility providing device-aware optimizations
 * Adapts behavior based on available system resources
 */
public class PerformanceOptimizer {
    private static final String TAG = "PerformanceOptimizer";
    private static PerformanceOptimizer instance;
    
    private final Context context;
    private final ActivityManager activityManager;
    private final boolean isLowMemoryDevice;
    private final int availableMemoryMb;
    
    private PerformanceOptimizer(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.availableMemoryMb = getAvailableMemory();
        this.isLowMemoryDevice = availableMemoryMb < 1024; // Less than 1GB
        
        Log.d(TAG, "Device memory: " + availableMemoryMb + "MB, Low memory device: " + isLowMemoryDevice);
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized PerformanceOptimizer getInstance(Context context) {
        if (instance == null) {
            instance = new PerformanceOptimizer(context);
        }
        return instance;
    }
    
    /**
     * Get available system memory in MB
     */
    private int getAvailableMemory() {
        try {
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            if (activityManager != null) {
                activityManager.getMemoryInfo(memInfo);
                return (int) (memInfo.totalMem / 1048576L); // Convert to MB
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get memory info: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Get optimized frame rate based on device capabilities
     * Higher end devices can handle higher frame rates
     */
    public int getOptimizedFrameRate(int requestedFrameRate) {
        if (isLowMemoryDevice) {
            // Cap at 30fps for low memory devices
            return Math.min(requestedFrameRate, 30);
        } else if (availableMemoryMb < 2048) {
            // Cap at 45fps for mid-range devices
            return Math.min(requestedFrameRate, 45);
        }
        // No limit for high-end devices
        return requestedFrameRate;
    }
    
    /**
     * Get optimized buffer size based on device memory
     * Prevents OOM on low-memory devices
     */
    public int getOptimizedBufferSize(int baseSize) {
        if (isLowMemoryDevice) {
            // Use 75% of base size on low memory devices
            return (baseSize * 3) / 4;
        } else if (availableMemoryMb < 2048) {
            // Use 90% of base size on mid-range devices
            return (baseSize * 9) / 10;
        }
        // Use full size on high-end devices
        return baseSize;
    }
    
    /**
     * Determine if aggressive garbage collection should be enabled
     */
    public boolean shouldUseAggressiveGC() {
        return isLowMemoryDevice;
    }
    
    /**
     * Get thread pool size recommendation
     */
    public int getOptimalThreadPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        if (isLowMemoryDevice) {
            // Use single thread on low memory devices
            return 1;
        } else if (availableMemoryMb < 2048) {
            // Use min(2, processors) on mid-range
            return Math.min(2, processors);
        }
        // Use processors/2 + 1 on high-end (standard formula)
        return Math.max(processors / 2 + 1, 1);
    }
    
    /**
     * Check if device has enough memory for high-quality operations
     */
    public boolean hasEnoughMemoryForHighQuality() {
        return availableMemoryMb >= 2048;
    }
    
    /**
     * Get memory cache size in MB
     */
    public int getMemoryCacheSize() {
        if (isLowMemoryDevice) {
            return 2; // 2MB for low memory devices
        } else if (availableMemoryMb < 2048) {
            return 5; // 5MB for mid-range
        }
        return 10; // 10MB for high-end
    }
    
    public boolean isLowMemoryDevice() {
        return isLowMemoryDevice;
    }
    
    public int getAvailableMemoryMb() {
        return availableMemoryMb;
    }
}
