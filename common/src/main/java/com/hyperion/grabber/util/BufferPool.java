package com.hyperion.grabber.common.util;

import android.util.Log;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Object pool for byte arrays to reduce garbage collection pressure
 * Reuses allocated buffers instead of creating new ones
 */
public class BufferPool {
    private static final String TAG = "BufferPool";
    private static BufferPool instance;
    
    private final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private final int bufferSize;
    private final int maxPoolSize;
    private int poolHits = 0;
    private int poolMisses = 0;
    
    private BufferPool(int bufferSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        Log.d(TAG, "BufferPool initialized: size=" + bufferSize + " bytes, max=" + maxPoolSize);
    }
    
    /**
     * Get or create singleton with specified buffer size
     */
    public static synchronized BufferPool getInstance(int bufferSize, int maxPoolSize) {
        if (instance == null) {
            instance = new BufferPool(bufferSize, maxPoolSize);
        }
        return instance;
    }
    
    /**
     * Acquire a buffer from the pool or create a new one
     */
    public byte[] acquire() {
        byte[] buffer = pool.poll();
        if (buffer != null) {
            poolHits++;
            if (poolHits % 100 == 0) {
                Log.d(TAG, "Pool stats - Hits: " + poolHits + ", Misses: " + poolMisses);
            }
            return buffer;
        }
        
        poolMisses++;
        return new byte[bufferSize];
    }
    
    /**
     * Release a buffer back to the pool
     */
    public void release(byte[] buffer) {
        if (buffer != null && buffer.length == bufferSize && pool.size() < maxPoolSize) {
            pool.offer(buffer);
        }
    }
    
    /**
     * Clear the pool
     */
    public void clear() {
        pool.clear();
        Log.d(TAG, "BufferPool cleared");
    }
    
    /**
     * Get pool statistics
     */
    public String getStats() {
        return "BufferPool stats: Size=" + pool.size() + 
               ", Hits=" + poolHits + ", Misses=" + poolMisses +
               ", HitRate=" + String.format("%.2f%%", 
               (poolHits * 100.0) / (poolHits + poolMisses));
    }
}
