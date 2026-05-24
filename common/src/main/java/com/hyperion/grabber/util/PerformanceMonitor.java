package com.hyperion.grabber.common.util;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performance monitoring utility for tracking operation metrics
 * Helps identify bottlenecks and performance regressions
 */
public class PerformanceMonitor {
    private static final String TAG = "PerformanceMonitor";
    private static final boolean DEBUG = false;
    
    private static class Metric {
        long count = 0;
        long totalTime = 0;
        long maxTime = 0;
        long minTime = Long.MAX_VALUE;
        
        void record(long duration) {
            count++;
            totalTime += duration;
            maxTime = Math.max(maxTime, duration);
            minTime = Math.min(minTime, duration);
        }
        
        double getAverage() {
            return count > 0 ? (double) totalTime / count : 0;
        }
    }
    
    private static final ConcurrentHashMap<String, Metric> metrics = new ConcurrentHashMap<>();
    
    /**
     * Start timing an operation
     */
    public static long startTimer() {
        return System.nanoTime();
    }
    
    /**
     * End timing and record metric
     */
    public static void recordMetric(String name, long startTime) {
        long duration = System.nanoTime() - startTime;
        Metric metric = metrics.computeIfAbsent(name, k -> new Metric());
        metric.record(duration);
        
        if (DEBUG && metric.count % 100 == 0) {
            Log.d(TAG, String.format("%s - Avg: %.2fms, Max: %dms, Min: %dms, Count: %d",
                    name,
                    metric.getAverage() / 1_000_000.0,
                    metric.maxTime / 1_000_000,
                    metric.minTime / 1_000_000,
                    metric.count));
        }
    }
    
    /**
     * Get metric statistics
     */
    public static String getMetrics(String name) {
        Metric metric = metrics.get(name);
        if (metric == null) return "No metrics for: " + name;
        
        return String.format("%s - Average: %.2fms, Max: %dms, Min: %dms, Count: %d, Total: %.2fs",
                name,
                metric.getAverage() / 1_000_000.0,
                metric.maxTime / 1_000_000,
                metric.minTime / 1_000_000,
                metric.count,
                metric.totalTime / 1_000_000_000.0);
    }
    
    /**
     * Clear all metrics
     */
    public static void clearAll() {
        metrics.clear();
        Log.d(TAG, "All metrics cleared");
    }
    
    /**
     * Print all metrics
     */
    public static void printAll() {
        Log.d(TAG, "=== Performance Metrics ===");
        for (String name : metrics.keySet()) {
            Log.d(TAG, getMetrics(name));
        }
        Log.d(TAG, "=========================");
    }
}
