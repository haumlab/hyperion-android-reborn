package com.hyperion.grabber.common;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import rikka.shizuku.Shizuku;

/**
 * Shizuku-based screen capture service.
 * 
 * This service runs with shell (ADB) permissions via Shizuku,
 * enabling SurfaceControl-based screen capture without root.
 * 
 * The key insight is that Shizuku runs code in a privileged process,
 * so SurfaceControl calls made here will succeed.
 */
public class ShizukuScreenCaptureService {
    private static final String TAG = "ShizukuCapture";
    
    // SurfaceControl reflection
    private static Class<?> sSurfaceControlClass;
    private static Method sCreateDisplay;
    private static Method sDestroyDisplay;
    private static Method sOpenTransaction;
    private static Method sCloseTransaction;
    private static Method sSetDisplaySurface;
    private static Method sSetDisplayProjection;
    private static Method sSetDisplayLayerStack;
    private static Method sGetBuiltInDisplay;
    private static Method sGetInternalDisplayToken;
    private static Method sGetPhysicalDisplayIds;
    private static Method sGetPhysicalDisplayToken;
    
    private static boolean sInitialized = false;
    private static boolean sAvailable = false;
    
    private IBinder mDisplay;
    private ImageReader mImageReader;
    private Surface mSurface;
    
    private int mWidth;
    private int mHeight;
    
    /**
     * Initialize reflection for SurfaceControl.
     * This should be called from within a Shizuku context.
     */
    public static synchronized void initialize() {
        if (sInitialized) return;
        sInitialized = true;
        
        try {
            sSurfaceControlClass = Class.forName("android.view.SurfaceControl");
            
            sOpenTransaction = sSurfaceControlClass.getMethod("openTransaction");
            sCloseTransaction = sSurfaceControlClass.getMethod("closeTransaction");
            sCreateDisplay = sSurfaceControlClass.getMethod("createDisplay", String.class, boolean.class);
            sDestroyDisplay = sSurfaceControlClass.getMethod("destroyDisplay", IBinder.class);
            sSetDisplaySurface = sSurfaceControlClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            sSetDisplayProjection = sSurfaceControlClass.getMethod("setDisplayProjection", 
                    IBinder.class, int.class, Rect.class, Rect.class);
            sSetDisplayLayerStack = sSurfaceControlClass.getMethod("setDisplayLayerStack", 
                    IBinder.class, int.class);
            
            // Get display token method varies by Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    sGetInternalDisplayToken = sSurfaceControlClass.getMethod("getInternalDisplayToken");
                } catch (NoSuchMethodException e) {
                    // Try physical display methods
                    sGetPhysicalDisplayIds = sSurfaceControlClass.getMethod("getPhysicalDisplayIds");
                    sGetPhysicalDisplayToken = sSurfaceControlClass.getMethod("getPhysicalDisplayToken", long.class);
                }
            } else {
                sGetBuiltInDisplay = sSurfaceControlClass.getMethod("getBuiltInDisplay", int.class);
            }
            
            sAvailable = true;
            Log.i(TAG, "SurfaceControl initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SurfaceControl: " + e.getMessage());
            sAvailable = false;
        }
    }
    
    /**
     * Check if SurfaceControl capture is available.
     */
    public static boolean isAvailable() {
        if (!sInitialized) initialize();
        return sAvailable;
    }
    
    /**
     * Get the built-in display token.
     */
    private static IBinder getDisplayToken() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (sGetInternalDisplayToken != null) {
                return (IBinder) sGetInternalDisplayToken.invoke(null);
            } else if (sGetPhysicalDisplayIds != null && sGetPhysicalDisplayToken != null) {
                long[] ids = (long[]) sGetPhysicalDisplayIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    return (IBinder) sGetPhysicalDisplayToken.invoke(null, ids[0]);
                }
            }
        } else if (sGetBuiltInDisplay != null) {
            return (IBinder) sGetBuiltInDisplay.invoke(null, 0);
        }
        return null;
    }
    
    /**
     * Create a virtual display for capture.
     */
    public boolean createDisplay(int width, int height) {
        if (!sAvailable) {
            Log.e(TAG, "SurfaceControl not available");
            return false;
        }
        
        mWidth = width;
        mHeight = height;
        
        try {
            // Create ImageReader for capture
            mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            mSurface = mImageReader.getSurface();
            
            // Create virtual display
            mDisplay = (IBinder) sCreateDisplay.invoke(null, "shizuku-capture", false);
            if (mDisplay == null) {
                Log.e(TAG, "Failed to create display");
                return false;
            }
            
            // Configure the display
            sOpenTransaction.invoke(null);
            try {
                sSetDisplaySurface.invoke(null, mDisplay, mSurface);
                sSetDisplayProjection.invoke(null, mDisplay, 0,
                        new Rect(0, 0, mWidth, mHeight),
                        new Rect(0, 0, width, height));
                sSetDisplayLayerStack.invoke(null, mDisplay, 0);
            } finally {
                sCloseTransaction.invoke(null);
            }
            
            Log.i(TAG, "Display created: " + width + "x" + height);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create display: " + e.getMessage());
            cleanup();
            return false;
        }
    }
    
    /**
     * Capture a frame and return RGB data.
     */
    public byte[] captureFrame() {
        if (mImageReader == null) {
            return null;
        }
        
        try {
            Image image = mImageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            
            try {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                
                // Extract RGB data
                byte[] rgbData = new byte[mWidth * mHeight * 3];
                int offset = 0;
                
                for (int y = 0; y < mHeight; y++) {
                    for (int x = 0; x < mWidth; x++) {
                        int idx = y * rowStride + x * pixelStride;
                        rgbData[offset++] = buffer.get(idx);     // R
                        rgbData[offset++] = buffer.get(idx + 1); // G
                        rgbData[offset++] = buffer.get(idx + 2); // B
                    }
                }
                
                return rgbData;
                
            } finally {
                image.close();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Capture failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Clean up resources.
     */
    public void cleanup() {
        if (mDisplay != null) {
            try {
                sDestroyDisplay.invoke(null, mDisplay);
            } catch (Exception e) {
                Log.w(TAG, "Failed to destroy display: " + e.getMessage());
            }
            mDisplay = null;
        }
        
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        
        mSurface = null;
    }
}
