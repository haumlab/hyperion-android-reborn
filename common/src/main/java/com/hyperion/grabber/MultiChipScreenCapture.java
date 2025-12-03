package com.hyperion.grabber.common;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Multi-chip screen capture support using multiple capture methods.
 * Inspired by scrcpy's approach to support many different Android devices/chips.
 * 
 * Capture Methods (in order of preference):
 * 
 * 1. SURFACE_CONTROL - Uses hidden SurfaceControl API via reflection
 *    - Works on many devices (Samsung, OnePlus, Xiaomi, etc.)
 *    - May have better HDR support on some devices
 *    - Requires Android 5.0+
 * 
 * 2. DISPLAY_MANAGER - Uses DisplayManager.createVirtualDisplay (hidden method)
 *    - Works on most devices
 *    - Standard Android approach used by scrcpy
 *    - Requires reflection for full functionality
 * 
 * 3. MEDIA_PROJECTION - Standard MediaProjection API
 *    - Works on all devices with screen capture permission
 *    - Most compatible but may have HDR tonemapping issues
 *    - This is the fallback method
 * 
 * The class automatically detects which methods are available and uses
 * the best one for the device.
 */
@SuppressLint("PrivateApi")
public class MultiChipScreenCapture {
    private static final String TAG = "MultiChipCapture";
    private static final boolean DEBUG = true;
    
    /**
     * Available capture methods
     */
    public enum CaptureMethod {
        SURFACE_CONTROL,     // Hidden SurfaceControl API
        DISPLAY_MANAGER,     // DisplayManager hidden API
        MEDIA_PROJECTION,    // Standard MediaProjection
        NONE                 // No method available
    }
    
    // SurfaceControl class and methods (reflection)
    private static Class<?> sSurfaceControlClass;
    private static Method sOpenTransaction;
    private static Method sCloseTransaction;
    private static Method sCreateDisplay;
    private static Method sDestroyDisplay;
    private static Method sSetDisplaySurface;
    private static Method sSetDisplayProjection;
    private static Method sSetDisplayLayerStack;
    private static Method sGetBuiltInDisplay;
    private static Method sGetInternalDisplayToken;
    private static Method sGetPhysicalDisplayToken;
    private static Method sGetPhysicalDisplayIds;
    
    // DisplayManager hidden methods
    private static Method sCreateVirtualDisplay;
    
    // Capability flags
    private static boolean sInitialized = false;
    private static boolean sSurfaceControlAvailable = false;
    private static boolean sDisplayManagerAvailable = false;
    
    // Instance state
    private CaptureMethod mActiveMethod = CaptureMethod.NONE;
    private IBinder mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private Surface mSurface;
    
    private final int mWidth;
    private final int mHeight;
    private final int mDensity;
    
    /**
     * Initialize the static reflection methods.
     * Call this once at app start.
     */
    public static synchronized void initialize() {
        if (sInitialized) return;
        sInitialized = true;
        
        Log.i(TAG, "Initializing multi-chip capture support...");
        Log.i(TAG, "Android version: " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")");
        Log.i(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        
        // Try to load SurfaceControl methods
        initSurfaceControl();
        
        // Try to load DisplayManager hidden methods
        initDisplayManager();
        
        Log.i(TAG, "SurfaceControl available: " + sSurfaceControlAvailable);
        Log.i(TAG, "DisplayManager hidden API available: " + sDisplayManagerAvailable);
    }
    
    @SuppressLint("PrivateApi")
    private static void initSurfaceControl() {
        try {
            sSurfaceControlClass = Class.forName("android.view.SurfaceControl");
            
            // Transaction methods
            sOpenTransaction = sSurfaceControlClass.getMethod("openTransaction");
            sCloseTransaction = sSurfaceControlClass.getMethod("closeTransaction");
            
            // Display creation
            sCreateDisplay = sSurfaceControlClass.getMethod("createDisplay", String.class, boolean.class);
            sDestroyDisplay = sSurfaceControlClass.getMethod("destroyDisplay", IBinder.class);
            
            // Display configuration
            sSetDisplaySurface = sSurfaceControlClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            sSetDisplayProjection = sSurfaceControlClass.getMethod("setDisplayProjection", 
                    IBinder.class, int.class, Rect.class, Rect.class);
            sSetDisplayLayerStack = sSurfaceControlClass.getMethod("setDisplayLayerStack", 
                    IBinder.class, int.class);
            
            // Get built-in display token
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+
                try {
                    sGetInternalDisplayToken = sSurfaceControlClass.getMethod("getInternalDisplayToken");
                } catch (NoSuchMethodException e) {
                    Log.d(TAG, "getInternalDisplayToken not available");
                }
            } else {
                // Android 9 and below
                try {
                    sGetBuiltInDisplay = sSurfaceControlClass.getMethod("getBuiltInDisplay", int.class);
                } catch (NoSuchMethodException e) {
                    Log.d(TAG, "getBuiltInDisplay not available");
                }
            }
            
            // Physical display methods (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    sGetPhysicalDisplayIds = sSurfaceControlClass.getMethod("getPhysicalDisplayIds");
                    sGetPhysicalDisplayToken = sSurfaceControlClass.getMethod("getPhysicalDisplayToken", long.class);
                } catch (NoSuchMethodException e) {
                    Log.d(TAG, "Physical display methods not available");
                }
            }
            
            sSurfaceControlAvailable = true;
            Log.i(TAG, "SurfaceControl API loaded successfully");
            
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "SurfaceControl class not found");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "SurfaceControl method not found: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Failed to init SurfaceControl: " + e.getMessage());
        }
    }
    
    @SuppressLint("PrivateApi")
    private static void initDisplayManager() {
        try {
            // Try to get the hidden createVirtualDisplay method
            Class<?> dmClass = android.hardware.display.DisplayManager.class;
            
            // Check for the hidden method that allows display mirroring without MediaProjection
            sCreateVirtualDisplay = dmClass.getMethod("createVirtualDisplay",
                    String.class, int.class, int.class, int.class, Surface.class);
            
            sDisplayManagerAvailable = true;
            Log.i(TAG, "DisplayManager hidden API loaded successfully");
            
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "DisplayManager hidden createVirtualDisplay not available");
        } catch (Exception e) {
            Log.w(TAG, "Failed to init DisplayManager hidden API: " + e.getMessage());
        }
    }
    
    /**
     * Create a new multi-chip screen capture instance.
     */
    public MultiChipScreenCapture(int width, int height, int density) {
        if (!sInitialized) {
            initialize();
        }
        
        mWidth = width;
        mHeight = height;
        mDensity = density;
    }
    
    /**
     * Detect the best capture method for this device.
     */
    public CaptureMethod detectBestMethod() {
        // Check if Shizuku is available - enables SurfaceControl for regular apps
        boolean hasShizuku = ShizukuHelper.canUseSurfaceControl();
        Log.i(TAG, "Shizuku available for capture: " + hasShizuku);
        
        // First try SurfaceControl API (only works with Shizuku or system permissions)
        if (sSurfaceControlAvailable && hasShizuku) {
            if (testSurfaceControlCapture()) {
                Log.i(TAG, "Best method: SURFACE_CONTROL (via Shizuku)");
                return CaptureMethod.SURFACE_CONTROL;
            }
        }
        
        // Then try DisplayManager hidden API (also needs Shizuku)
        if (sDisplayManagerAvailable && hasShizuku) {
            if (testDisplayManagerCapture()) {
                Log.i(TAG, "Best method: DISPLAY_MANAGER (via Shizuku)");
                return CaptureMethod.DISPLAY_MANAGER;
            }
        }
        
        // Fallback to MediaProjection
        Log.i(TAG, "Best method: MEDIA_PROJECTION (fallback)");
        return CaptureMethod.MEDIA_PROJECTION;
    }
    
    /**
     * Test if SurfaceControl capture works on this device.
     * Requires Shizuku or system permissions to actually capture frames.
     */
    private boolean testSurfaceControlCapture() {
        // Without Shizuku, SurfaceControl won't work for regular apps
        if (!ShizukuHelper.canUseSurfaceControl()) {
            Log.d(TAG, "SurfaceControl: Shizuku not available");
            return false;
        }
        
        try {
            // Try to get the display token
            IBinder displayToken = getBuiltInDisplayToken();
            if (displayToken == null) {
                Log.d(TAG, "SurfaceControl: No display token");
                return false;
            }
            
            // Try to create a virtual display
            IBinder testDisplay = createSurfaceControlDisplay("test", false);
            if (testDisplay != null) {
                destroySurfaceControlDisplay(testDisplay);
                Log.d(TAG, "SurfaceControl: Test successful with Shizuku!");
                return true;  // Shizuku provides the permissions we need
            }
            
        } catch (Exception e) {
            Log.d(TAG, "SurfaceControl test failed: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Test if DisplayManager hidden API works on this device.
     */
    private boolean testDisplayManagerCapture() {
        // This requires shell/system permissions - check if Shizuku provides them
        return ShizukuHelper.canUseSurfaceControl() && sDisplayManagerAvailable;
    }
    
    /**
     * Get the built-in display token using available methods.
     */
    public static IBinder getBuiltInDisplayToken() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sGetInternalDisplayToken != null) {
                return (IBinder) sGetInternalDisplayToken.invoke(null);
            } else if (sGetBuiltInDisplay != null) {
                return (IBinder) sGetBuiltInDisplay.invoke(null, 0);
            }
            
            // Try physical display methods
            if (sGetPhysicalDisplayIds != null && sGetPhysicalDisplayToken != null) {
                long[] ids = (long[]) sGetPhysicalDisplayIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    return (IBinder) sGetPhysicalDisplayToken.invoke(null, ids[0]);
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to get display token: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Create a virtual display using SurfaceControl API.
     */
    public static IBinder createSurfaceControlDisplay(String name, boolean secure) throws Exception {
        if (sCreateDisplay == null) {
            throw new UnsupportedOperationException("SurfaceControl.createDisplay not available");
        }
        
        // On Android 12+, secure displays can't be created with shell permissions
        boolean canBeSecure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME));
        
        return (IBinder) sCreateDisplay.invoke(null, name, secure && canBeSecure);
    }
    
    /**
     * Destroy a virtual display created with SurfaceControl.
     */
    public static void destroySurfaceControlDisplay(IBinder display) {
        if (sDestroyDisplay != null && display != null) {
            try {
                sDestroyDisplay.invoke(null, display);
            } catch (Exception e) {
                Log.w(TAG, "Failed to destroy display: " + e.getMessage());
            }
        }
    }
    
    /**
     * Configure a SurfaceControl display.
     */
    public static void configureSurfaceControlDisplay(IBinder display, Surface surface, 
            Rect sourceRect, Rect destRect, int layerStack) throws Exception {
        if (sOpenTransaction == null || sCloseTransaction == null) {
            throw new UnsupportedOperationException("SurfaceControl transactions not available");
        }
        
        sOpenTransaction.invoke(null);
        try {
            if (sSetDisplaySurface != null) {
                sSetDisplaySurface.invoke(null, display, surface);
            }
            if (sSetDisplayProjection != null) {
                sSetDisplayProjection.invoke(null, display, 0, sourceRect, destRect);
            }
            if (sSetDisplayLayerStack != null) {
                sSetDisplayLayerStack.invoke(null, display, layerStack);
            }
        } finally {
            sCloseTransaction.invoke(null);
        }
    }
    
    /**
     * Start capture using SurfaceControl API.
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public boolean startSurfaceControlCapture(ImageReader imageReader) {
        try {
            mDisplay = createSurfaceControlDisplay("hyperion", false);
            if (mDisplay == null) {
                Log.w(TAG, "Failed to create SurfaceControl display");
                return false;
            }
            
            mImageReader = imageReader;
            mSurface = imageReader.getSurface();
            
            Rect sourceRect = new Rect(0, 0, mWidth, mHeight);
            Rect destRect = new Rect(0, 0, imageReader.getWidth(), imageReader.getHeight());
            
            // Use layer stack 0 (main display)
            configureSurfaceControlDisplay(mDisplay, mSurface, sourceRect, destRect, 0);
            
            mActiveMethod = CaptureMethod.SURFACE_CONTROL;
            Log.i(TAG, "SurfaceControl capture started");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SurfaceControl capture: " + e.getMessage());
            stopCapture();
            return false;
        }
    }
    
    /**
     * Start capture using MediaProjection (standard method).
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean startMediaProjectionCapture(MediaProjection projection, ImageReader imageReader, 
            int displayFlags, Handler handler) {
        try {
            mImageReader = imageReader;
            mSurface = imageReader.getSurface();
            
            mVirtualDisplay = projection.createVirtualDisplay(
                    "hyperion",
                    imageReader.getWidth(),
                    imageReader.getHeight(),
                    mDensity,
                    displayFlags,
                    mSurface,
                    null,
                    handler);
            
            if (mVirtualDisplay == null) {
                Log.w(TAG, "Failed to create MediaProjection virtual display");
                return false;
            }
            
            mActiveMethod = CaptureMethod.MEDIA_PROJECTION;
            Log.i(TAG, "MediaProjection capture started");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MediaProjection capture: " + e.getMessage());
            stopCapture();
            return false;
        }
    }
    
    /**
     * Stop all capture methods and release resources.
     */
    public void stopCapture() {
        if (mDisplay != null) {
            destroySurfaceControlDisplay(mDisplay);
            mDisplay = null;
        }
        
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        // Note: Don't close mImageReader here - it's owned by the caller
        mImageReader = null;
        mSurface = null;
        mActiveMethod = CaptureMethod.NONE;
    }
    
    /**
     * Get the currently active capture method.
     */
    public CaptureMethod getActiveMethod() {
        return mActiveMethod;
    }
    
    /**
     * Check if SurfaceControl API is available on this device.
     */
    public static boolean isSurfaceControlAvailable() {
        if (!sInitialized) initialize();
        return sSurfaceControlAvailable;
    }
    
    /**
     * Check if DisplayManager hidden API is available on this device.
     */
    public static boolean isDisplayManagerHiddenApiAvailable() {
        if (!sInitialized) initialize();
        return sDisplayManagerAvailable;
    }
    
    /**
     * Get a string describing the device's capture capabilities.
     */
    public static String getCapabilitiesString() {
        if (!sInitialized) initialize();
        
        boolean hasShizuku = ShizukuHelper.canUseSurfaceControl();
        
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Chip: ").append(Build.HARDWARE).append("\n");
        sb.append("Android: ").append(Build.VERSION.SDK_INT).append(" (").append(Build.VERSION.RELEASE).append(")\n\n");
        
        // Shizuku status
        sb.append("Shizuku: ");
        if (hasShizuku) {
            sb.append("✓ Ready");
        } else if (ShizukuHelper.isShizukuAvailable()) {
            sb.append("Running (need permission)");
        } else {
            sb.append("Not running");
        }
        sb.append("\n\n");
        
        // SurfaceControl status
        sb.append("SurfaceControl: ");
        if (sSurfaceControlAvailable && hasShizuku) {
            sb.append("✓ Available");
        } else if (sSurfaceControlAvailable) {
            sb.append("API found (needs Shizuku)");
        } else {
            sb.append("Not available");
        }
        
        return sb.toString();
    }
}
