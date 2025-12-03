package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import android.util.Log;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.BorderProcessor;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;

import java.nio.ByteBuffer;

/**
 * Hybrid screen encoder that automatically selects the best capture method:
 * 
 * 1. Hardware VPU Grabber (Amlogic, MediaTek, Rockchip) - No performance impact, HDR support
 * 2. Standard MediaProjection (fallback) - May cause HDR performance issues
 * 
 * The hardware grabber captures directly from the video decoder, bypassing
 * Android's compositor and avoiding the HDR tonemapping overhead.
 * 
 * Supported SoCs:
 * - Amlogic: S905, S912, S922, A311D, etc.
 * - MediaTek: MT8167, MT8173, MT8695, etc.
 * - Rockchip: RK3328, RK3399, etc.
 */
public class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = true; // Enable for testing
    
    // Capture configuration
    private static final int CAPTURE_WIDTH = 64;
    private static final int CAPTURE_HEIGHT = 36;
    private static final int MAX_IMAGE_READER_IMAGES = 1;
    private static final long CAPTURE_INTERVAL_MS = 33; // ~30 FPS target
    
    // Capture method selection
    private enum CaptureMethod {
        HARDWARE_VPU,      // Direct VPU capture (Amlogic/MediaTek/Rockchip) - best for HDR
        MEDIA_PROJECTION   // Standard Android API - fallback
    }
    
    private CaptureMethod mCaptureMethod = CaptureMethod.MEDIA_PROJECTION;
    private HardwareGrabber mHardwareGrabber;
    
    // MediaProjection resources (only used for fallback)
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    
    // Capture thread
    private Thread mCaptureThread;
    private volatile boolean mRunning = false;
    
    // Performance tracking
    private long mLastFrameTime = 0;
    private int mFrameCount = 0;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    HyperionScreenEncoder(final HyperionThread.HyperionThreadListener listener,
                           final MediaProjection projection, final int width, final int height,
                           final int density, HyperionGrabberOptions options) {
        super(listener, projection, width, height, density, options);

        // Try to initialize Amlogic grabber first
        initializeGrabber();
        
        try {
            prepare();
        } catch (MediaCodec.CodecException e) {
            e.printStackTrace();
        }
    }
    
    private void initializeGrabber() {
        try {
            mHardwareGrabber = new HardwareGrabber(CAPTURE_WIDTH, CAPTURE_HEIGHT);
            
            if (mHardwareGrabber.isAvailable()) {
                mCaptureMethod = CaptureMethod.HARDWARE_VPU;
                HardwareGrabber.SocType socType = mHardwareGrabber.getSocType();
                String devicePath = mHardwareGrabber.getActiveDevice();
                Log.i(TAG, "Using " + socType + " hardware grabber - HDR capture enabled!");
                Log.i(TAG, "Capture device: " + devicePath);
            } else {
                mCaptureMethod = CaptureMethod.MEDIA_PROJECTION;
                Log.i(TAG, "No hardware grabber available, using MediaProjection fallback");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize hardware grabber: " + e.getMessage());
            mCaptureMethod = CaptureMethod.MEDIA_PROJECTION;
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void prepare() throws MediaCodec.CodecException {
        if (DEBUG) Log.d(TAG, "Preparing encoder with method: " + mCaptureMethod);

        if (mCaptureMethod == CaptureMethod.MEDIA_PROJECTION) {
            // Setup MediaProjection fallback
            mImageReader = ImageReader.newInstance(
                    CAPTURE_WIDTH,
                    CAPTURE_HEIGHT,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGE_READER_IMAGES
            );

            int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    TAG,
                    CAPTURE_WIDTH, 
                    CAPTURE_HEIGHT, 
                    1,
                    displayFlags,
                    mImageReader.getSurface(),
                    null,
                    null);
        }

        startCaptureThread();
    }

    private void startCaptureThread() {
        mRunning = true;
        setCapturing(true);
        
        mCaptureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Use lower priority to not interfere with video playback
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                
                while (mRunning) {
                    long startTime = SystemClock.elapsedRealtime();
                    
                    // Capture frame using selected method
                    boolean success = false;
                    
                    if (mCaptureMethod == CaptureMethod.HARDWARE_VPU) {
                        success = captureViaHardware();
                    } else {
                        success = captureViaMediaProjection();
                    }
                    
                    if (success) {
                        mFrameCount++;
                    }
                    
                    // Calculate sleep time to maintain target frame rate
                    long elapsed = SystemClock.elapsedRealtime() - startTime;
                    long sleepTime = Math.max(1, CAPTURE_INTERVAL_MS - elapsed);
                    
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "HyperionCapture");
        
        mCaptureThread.start();
    }
    
    /**
     * Capture using Hardware VPU - direct video decoder access (Amlogic/MediaTek/Rockchip)
     * This is the preferred method for HDR content
     */
    private boolean captureViaHardware() {
        if (mHardwareGrabber == null || mListener == null) {
            return false;
        }
        
        try {
            byte[] frame;
            
            if (mAvgColor) {
                // Get average color directly
                frame = mHardwareGrabber.captureAverageColor();
                if (frame != null && frame.length >= 3) {
                    mListener.sendFrame(frame, 1, 1);
                    return true;
                }
            } else {
                // Get full frame
                frame = mHardwareGrabber.captureFrame();
                if (frame != null && frame.length > 0) {
                    int pixels = frame.length / 3;
                    int width = CAPTURE_WIDTH;
                    int height = pixels / width;
                    if (height > 0) {
                        mListener.sendFrame(frame, width, height);
                        return true;
                    }
                }
            }
            
            // If hardware capture fails, video might not be playing
            // Fall back to MediaProjection temporarily
            if (mVirtualDisplay == null && mImageReader == null) {
                // We don't have fallback initialized - just skip this frame
                return false;
            }
            
            return captureViaMediaProjection();
            
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Hardware capture error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Capture using MediaProjection - fallback method
     * May cause performance issues with HDR content
     */
    private boolean captureViaMediaProjection() {
        if (mImageReader == null || mListener == null) {
            return false;
        }
        
        Image img = null;
        try {
            img = mImageReader.acquireLatestImage();
            if (img != null) {
                sendImage(img);
                return true;
            }
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "MediaProjection capture error: " + e.getMessage());
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording Called");
        mRunning = false;
        setCapturing(false);
        
        if (mCaptureThread != null) {
            mCaptureThread.interrupt();
            try {
                mCaptureThread.join(1000);
            } catch (InterruptedException ignored) {}
            mCaptureThread = null;
        }
        
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        if (mHandler != null && mHandler.getLooper() != null) {
            mHandler.getLooper().quit();
        }
        
        clearAndDisconnect();
        
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        
        if (DEBUG) {
            Log.i(TAG, "Captured " + mFrameCount + " frames total");
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording Called");
        if (!mRunning) {
            startCaptureThread();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void setOrientation(int orientation) {
        mCurrentOrientation = orientation;
    }

    private void sendImage(Image img) {
        if (img == null || mListener == null) return;
        
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length == 0) return;
        
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        if (buffer == null) return;

        int width = img.getWidth();
        int height = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();

        try {
            if (mAvgColor) {
                mListener.sendFrame(
                        computeAverageColor(buffer, width, height, rowStride, pixelStride),
                        1, 1);
            } else {
                mListener.sendFrame(
                        extractPixels(buffer, width, height, rowStride, pixelStride),
                        width, height);
            }
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Error sending frame: " + e.getMessage());
        }
    }
    
    private byte[] extractPixels(ByteBuffer buffer, int width, int height, 
                                  int rowStride, int pixelStride) {
        byte[] pixels = new byte[width * height * 3];
        int pixelIndex = 0;
        
        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x++) {
                int offset = rowOffset + x * pixelStride;
                pixels[pixelIndex++] = (byte) (buffer.get(offset) & 0xff);
                pixels[pixelIndex++] = (byte) (buffer.get(offset + 1) & 0xff);
                pixels[pixelIndex++] = (byte) (buffer.get(offset + 2) & 0xff);
            }
        }
        return pixels;
    }

    private byte[] computeAverageColor(ByteBuffer buffer, int width, int height,
                                       int rowStride, int pixelStride) {
        long totalR = 0, totalG = 0, totalB = 0;
        int count = 0;
        
        // Sample every other pixel for speed
        for (int y = 0; y < height; y += 2) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x += 2) {
                int offset = rowOffset + x * pixelStride;
                totalR += buffer.get(offset) & 0xff;
                totalG += buffer.get(offset + 1) & 0xff;
                totalB += buffer.get(offset + 2) & 0xff;
                count++;
            }
        }

        byte[] avg = new byte[3];
        if (count > 0) {
            avg[0] = (byte) (totalR / count);
            avg[1] = (byte) (totalG / count);
            avg[2] = (byte) (totalB / count);
        }
        return avg;
    }
    
    /**
     * Get current capture method for debugging
     */
    public String getCaptureMethodName() {
        if (mCaptureMethod == CaptureMethod.HARDWARE_VPU && mHardwareGrabber != null) {
            return "HARDWARE_VPU (" + mHardwareGrabber.getSocType() + ")";
        }
        return mCaptureMethod.name();
    }
    
    /**
     * Check if using hardware-accelerated HDR capture
     */
    public boolean isHDRCaptureEnabled() {
        return mCaptureMethod == CaptureMethod.HARDWARE_VPU;
    }
    
    /**
     * Get the SoC type if hardware capture is available
     */
    public HardwareGrabber.SocType getSocType() {
        if (mHardwareGrabber != null) {
            return mHardwareGrabber.getSocType();
        }
        return HardwareGrabber.SocType.UNKNOWN;
    }
}
