package com.hyperion.grabber;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Shizuku UserService implementation that runs in Shizuku's privileged process.
 * 
 * This service has shell (ADB) level permissions, allowing it to use
 * SurfaceControl APIs that normal apps cannot access.
 * 
 * The key is that this code runs IN Shizuku's process, not our app's process,
 * so it inherits Shizuku's elevated permissions.
 */
public class ShizukuCaptureServiceImpl extends IShizukuCaptureService.Stub {
    private static final String TAG = "ShizukuCaptureImpl";
    
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
    
    private static boolean sReflectionInitialized = false;
    private static boolean sSurfaceControlAvailable = false;
    
    // Capture state
    private IBinder mDisplay;
    private ImageReader mImageReader;
    private Surface mSurface;
    private int mWidth;
    private int mHeight;
    private boolean mCapturing = false;
    
    public ShizukuCaptureServiceImpl() {
        Log.i(TAG, "ShizukuCaptureServiceImpl created in process: " + android.os.Process.myPid());
        initReflection();
    }
    
    private static synchronized void initReflection() {
        if (sReflectionInitialized) return;
        sReflectionInitialized = true;
        
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
                    Log.i(TAG, "Using getInternalDisplayToken");
                } catch (NoSuchMethodException e) {
                    try {
                        sGetPhysicalDisplayIds = sSurfaceControlClass.getMethod("getPhysicalDisplayIds");
                        sGetPhysicalDisplayToken = sSurfaceControlClass.getMethod("getPhysicalDisplayToken", long.class);
                        Log.i(TAG, "Using getPhysicalDisplayToken");
                    } catch (NoSuchMethodException e2) {
                        Log.w(TAG, "No display token method found for Android Q+");
                    }
                }
            } else {
                try {
                    sGetBuiltInDisplay = sSurfaceControlClass.getMethod("getBuiltInDisplay", int.class);
                    Log.i(TAG, "Using getBuiltInDisplay");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "getBuiltInDisplay not found");
                }
            }
            
            sSurfaceControlAvailable = true;
            Log.i(TAG, "SurfaceControl reflection initialized successfully!");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SurfaceControl reflection: " + e.getMessage());
            sSurfaceControlAvailable = false;
        }
    }
    
    private IBinder getDisplayTokenInternal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (sGetInternalDisplayToken != null) {
                    IBinder token = (IBinder) sGetInternalDisplayToken.invoke(null);
                    Log.i(TAG, "getInternalDisplayToken returned: " + (token != null ? "valid token" : "null"));
                    return token;
                } else if (sGetPhysicalDisplayIds != null && sGetPhysicalDisplayToken != null) {
                    long[] ids = (long[]) sGetPhysicalDisplayIds.invoke(null);
                    Log.i(TAG, "Physical display IDs: " + (ids != null ? ids.length : 0));
                    if (ids != null && ids.length > 0) {
                        IBinder token = (IBinder) sGetPhysicalDisplayToken.invoke(null, ids[0]);
                        Log.i(TAG, "getPhysicalDisplayToken returned: " + (token != null ? "valid token" : "null"));
                        return token;
                    }
                }
            } else if (sGetBuiltInDisplay != null) {
                IBinder token = (IBinder) sGetBuiltInDisplay.invoke(null, 0);
                Log.i(TAG, "getBuiltInDisplay returned: " + (token != null ? "valid token" : "null"));
                return token;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get display token: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
    
    @Override
    public boolean initialize() throws RemoteException {
        Log.i(TAG, "initialize() called - SurfaceControl available: " + sSurfaceControlAvailable);
        return sSurfaceControlAvailable;
    }
    
    @Override
    public String getDisplayToken() throws RemoteException {
        IBinder token = getDisplayTokenInternal();
        if (token != null) {
            return "Display token obtained successfully! (" + token.toString() + ")";
        }
        return null;
    }
    
    @Override
    public boolean createDisplay(int width, int height) throws RemoteException {
        if (!sSurfaceControlAvailable) {
            Log.e(TAG, "SurfaceControl not available");
            return false;
        }
        
        // Clean up any existing capture
        release();
        
        mWidth = width;
        mHeight = height;
        
        try {
            Log.i(TAG, "Creating display: " + width + "x" + height);
            
            // Create ImageReader
            mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            mSurface = mImageReader.getSurface();
            Log.i(TAG, "ImageReader created");
            
            // Create virtual display via SurfaceControl
            mDisplay = (IBinder) sCreateDisplay.invoke(null, "shizuku-hyperion", false);
            if (mDisplay == null) {
                Log.e(TAG, "Failed to create SurfaceControl display");
                release();
                return false;
            }
            Log.i(TAG, "SurfaceControl display created");
            
            // Configure the display
            sOpenTransaction.invoke(null);
            try {
                sSetDisplaySurface.invoke(null, mDisplay, mSurface);
                sSetDisplayProjection.invoke(null, mDisplay, 0,
                        new Rect(0, 0, mWidth, mHeight),
                        new Rect(0, 0, width, height));
                sSetDisplayLayerStack.invoke(null, mDisplay, 0);
                Log.i(TAG, "Display configured");
            } finally {
                sCloseTransaction.invoke(null);
            }
            
            mCapturing = true;
            Log.i(TAG, "Capture started successfully!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create display: " + e.getMessage());
            e.printStackTrace();
            release();
            return false;
        }
    }
    
    @Override
    public byte[] captureFrame() throws RemoteException {
        if (!mCapturing || mImageReader == null) {
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
            Log.e(TAG, "Capture frame failed: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public void release() throws RemoteException {
        Log.i(TAG, "release() called");
        mCapturing = false;
        
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
    
    @Override
    public boolean isCapturing() throws RemoteException {
        return mCapturing;
    }
}
