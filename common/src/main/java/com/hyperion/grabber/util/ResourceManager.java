package com.hyperion.grabber.common.util;

import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages lifecycle of resources to ensure proper cleanup
 * Prevents resource leaks and improves application stability
 */
public class ResourceManager {
    private static final String TAG = "ResourceManager";
    
    private final CopyOnWriteArrayList<Resource> resources = new CopyOnWriteArrayList<>();
    
    /**
     * Register a resource for lifecycle management
     */
    public void register(Resource resource) {
        if (resource != null) {
            resources.add(resource);
            Log.d(TAG, "Resource registered: " + resource.getClass().getSimpleName());
        }
    }
    
    /**
     * Unregister a resource
     */
    public void unregister(Resource resource) {
        if (resources.remove(resource)) {
            Log.d(TAG, "Resource unregistered: " + resource.getClass().getSimpleName());
        }
    }
    
    /**
     * Release all managed resources
     */
    public void releaseAll() {
        for (Resource resource : resources) {
            try {
                resource.release();
                Log.d(TAG, "Resource released: " + resource.getClass().getSimpleName());
            } catch (Exception e) {
                Log.e(TAG, "Error releasing resource: " + e.getMessage(), e);
            }
        }
        resources.clear();
    }
    
    /**
     * Get count of managed resources
     */
    public int getResourceCount() {
        return resources.size();
    }
    
    /**
     * Resource interface for lifecycle management
     */
    public interface Resource {
        /**
         * Called when resource should be released
         */
        void release();
    }
}
