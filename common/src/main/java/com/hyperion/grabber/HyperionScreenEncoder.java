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
 * Ultra-lightweight screen encoder optimized for HDR video playback on non-rooted devices.
 * 
 * THE PROBLEM: MediaProjection forces HDR tonemapping at the compositor level,
 * causing system-wide lag regardless of capture resolution or frame rate.
 * 
 * THE SOLUTION: Make capture SO lightweight that the compositor overhead is minimized:
 * 
 * 1. TINY capture resolution (16x9 pixels) - compositor scales down, less work
 * 2. Very low frame rate (5-10 FPS) - ambient lighting doesn't need 30fps
 * 3. Adaptive throttling - backs off further when system is stressed
 * 4. Lowest thread priority - never competes with video decoder
 * 5. Temporal smoothing - smooth colors even at low frame rate
 * 6. Immediate image release - minimizes compositor buffer pressure
 * 
 * For ambient lighting, we only need the general color mood of the screen,
 * not high fidelity capture. 16x9 = 144 pixels is MORE than enough.
 */
public class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;
    
    // ULTRA-MINIMAL capture - absolute minimum for ambient lighting
    // 16x9 = 144 pixels total. The compositor scales the entire screen to this.
    // Smaller = MUCH less compositor work = less HDR interference
    private static final int CAPTURE_WIDTH = 16;
    private static final int CAPTURE_HEIGHT = 9;
    
    // Single buffer - we grab and release immediately
    private static final int MAX_IMAGE_READER_IMAGES = 1;
    
    // Very slow capture rate - ambient lighting doesn't need fast updates
    // This is the KEY to reducing lag - less frequent capture = less compositor stress
    private static final long BASE_INTERVAL_MS = 100;      // 10 FPS base (already slow)
    private static final long THROTTLE_INTERVAL_MS = 200;  // 5 FPS when throttled
    private static final long EMERGENCY_INTERVAL_MS = 500; // 2 FPS emergency mode
    
    // Adaptive throttling based on capture performance
    private long mCurrentInterval = BASE_INTERVAL_MS;
    private long mLastSuccessfulCapture = 0;
    private int mConsecutiveSlowFrames = 0;
    private int mConsecutiveFastFrames = 0;
    private static final int THROTTLE_THRESHOLD = 3;
    private static final int UNTHROTTLE_THRESHOLD = 10;
    private static final long SLOW_FRAME_MS = 50; // If capture takes > 50ms, it's slow
    
    // Temporal smoothing - blend colors over time for smoother ambient effect
    private byte[] mLastColor = new byte[]{0, 0, 0};
    private static final float SMOOTHING_FACTOR = 0.7f; // 70% new, 30% old

    // MediaProjection resources
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    
    // Capture thread
    private Thread mCaptureThread;
    private volatile boolean mRunning = false;
    
    // Stats
    private int mFrameCount = 0;
    private int mThrottleCount = 0;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    HyperionScreenEncoder(final HyperionThread.HyperionThreadListener listener,
                           final MediaProjection projection, final int width, final int height,
                           final int density, HyperionGrabberOptions options) {
        super(listener, projection, width, height, density, options);

        try {
            prepare();
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Failed to prepare encoder", e);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void prepare() throws MediaCodec.CodecException {
        Log.i(TAG, "Preparing ultra-lightweight encoder: " + CAPTURE_WIDTH + "x" + CAPTURE_HEIGHT + 
              " @ " + (1000 / BASE_INTERVAL_MS) + " FPS base");

        // Create tiny ImageReader
        mImageReader = ImageReader.newInstance(
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                PixelFormat.RGBA_8888,
                MAX_IMAGE_READER_IMAGES
        );

        // Use minimal flags - PUBLIC is required but avoid any extras
        int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        
        // Use lowest possible DPI (1) to minimize scaling work
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                1,  // Minimal DPI
                displayFlags,
                mImageReader.getSurface(),
                null,
                null);

        startCaptureThread();
    }

    private void startCaptureThread() {
        mRunning = true;
        setCapturing(true);
        
        mCaptureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // LOWEST priority - never interfere with video playback
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
                
                Log.i(TAG, "Capture thread started with LOWEST priority");
                
                while (mRunning) {
                    long frameStart = SystemClock.elapsedRealtime();
                    
                    // Attempt capture
                    boolean success = captureFrame();
                    
                    long captureTime = SystemClock.elapsedRealtime() - frameStart;
                    
                    // Adaptive throttling based on capture performance
                    adjustThrottling(captureTime, success);
                    
                    if (success) {
                        mFrameCount++;
                        mLastSuccessfulCapture = SystemClock.elapsedRealtime();
                    }
                    
                    // Sleep for current interval
                    long sleepTime = Math.max(10, mCurrentInterval - captureTime);
                    
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                
                Log.i(TAG, "Capture thread stopped. Frames: " + mFrameCount + 
                      ", Throttles: " + mThrottleCount);
            }
        }, "HyperionUltraLight");
        
        mCaptureThread.start();
    }
    
    /**
     * Adaptive throttling - backs off when system is stressed
     */
    private void adjustThrottling(long captureTime, boolean success) {
        if (!success || captureTime > SLOW_FRAME_MS) {
            // Frame was slow or failed
            mConsecutiveSlowFrames++;
            mConsecutiveFastFrames = 0;
            
            if (mConsecutiveSlowFrames >= THROTTLE_THRESHOLD) {
                // Throttle up
                if (mCurrentInterval < THROTTLE_INTERVAL_MS) {
                    mCurrentInterval = THROTTLE_INTERVAL_MS;
                    mThrottleCount++;
                    if (DEBUG) Log.d(TAG, "Throttling to " + mCurrentInterval + "ms");
                } else if (mCurrentInterval < EMERGENCY_INTERVAL_MS && mConsecutiveSlowFrames >= THROTTLE_THRESHOLD * 2) {
                    mCurrentInterval = EMERGENCY_INTERVAL_MS;
                    if (DEBUG) Log.d(TAG, "EMERGENCY throttle to " + mCurrentInterval + "ms");
                }
            }
        } else {
            // Frame was fast
            mConsecutiveFastFrames++;
            mConsecutiveSlowFrames = 0;
            
            if (mConsecutiveFastFrames >= UNTHROTTLE_THRESHOLD) {
                // Can try to speed up
                if (mCurrentInterval > BASE_INTERVAL_MS) {
                    mCurrentInterval = BASE_INTERVAL_MS;
                    if (DEBUG) Log.d(TAG, "Unthrottling to " + mCurrentInterval + "ms");
                }
                mConsecutiveFastFrames = 0;
            }
        }
    }

    /**
     * Capture a single frame with minimal overhead
     */
    private boolean captureFrame() {
        if (mImageReader == null || mListener == null) {
            return false;
        }
        
        Image img = null;
        try {
            // Acquire and process immediately
            img = mImageReader.acquireLatestImage();
            
            if (img == null) {
                return false;
            }
            
            // Extract color data as fast as possible
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) {
                return false;
            }
            
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            if (buffer == null) {
                return false;
            }
            
            int width = img.getWidth();
            int height = img.getHeight();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            
            // Close image ASAP to release compositor buffer
            byte[] frameData;
            if (mAvgColor) {
                frameData = computeAverageColorFast(buffer, width, height, rowStride, pixelStride);
            } else {
                frameData = extractPixelsFast(buffer, width, height, rowStride, pixelStride);
            }
            
            // Close image immediately after extracting data
            img.close();
            img = null;
            
            // Apply temporal smoothing for smoother ambient effect
            if (mAvgColor && frameData.length == 3) {
                frameData = applySmoothing(frameData);
            }
            
            // Send to Hyperion
            if (mAvgColor) {
                mListener.sendFrame(frameData, 1, 1);
            } else {
                mListener.sendFrame(frameData, width, height);
            }
            
            return true;
            
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Capture error: " + e.getMessage());
            return false;
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Ultra-fast average color computation
     * With only 144 pixels, we can sample all of them
     */
    private byte[] computeAverageColorFast(ByteBuffer buffer, int width, int height,
                                           int rowStride, int pixelStride) {
        int totalR = 0, totalG = 0, totalB = 0;
        int count = 0;
        
        // Sample all pixels - it's only 144 at 16x9
        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x++) {
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
     * Fast pixel extraction for the tiny frame
     */
    private byte[] extractPixelsFast(ByteBuffer buffer, int width, int height, 
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
    
    /**
     * Temporal smoothing - blend with previous color for smoother transitions
     * This lets us run at very low FPS while still looking smooth
     */
    private byte[] applySmoothing(byte[] newColor) {
        byte[] smoothed = new byte[3];
        
        for (int i = 0; i < 3; i++) {
            int newVal = newColor[i] & 0xFF;
            int oldVal = mLastColor[i] & 0xFF;
            int blended = (int) (newVal * SMOOTHING_FACTOR + oldVal * (1 - SMOOTHING_FACTOR));
            smoothed[i] = (byte) blended;
        }
        
        mLastColor = smoothed;
        return smoothed;
    }

    @Override
    public void stopRecording() {
        Log.i(TAG, "Stopping recording");
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
        
        Log.i(TAG, "Recording stopped. Total frames: " + mFrameCount);
    }

    @Override
    public void resumeRecording() {
        Log.i(TAG, "Resuming recording");
        if (!mRunning) {
            // Reset throttling state
            mCurrentInterval = BASE_INTERVAL_MS;
            mConsecutiveSlowFrames = 0;
            mConsecutiveFastFrames = 0;
            startCaptureThread();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void setOrientation(int orientation) {
        mCurrentOrientation = orientation;
    }
    
    /**
     * Get current capture interval (for debugging)
     */
    public long getCurrentInterval() {
        return mCurrentInterval;
    }
    
    /**
     * Check if currently throttled
     */
    public boolean isThrottled() {
        return mCurrentInterval > BASE_INTERVAL_MS;
    }
    
    /**
     * Get capture stats string
     */
    public String getStats() {
        return String.format("Frames: %d, Interval: %dms, Throttles: %d", 
                             mFrameCount, mCurrentInterval, mThrottleCount);
    }
}
