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
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.RequiresApi;
import android.util.Log;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.BorderProcessor;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;

import java.nio.ByteBuffer;

/**
 * High-performance screen encoder for Hyperion LED control.
 * Captures screen content at minimal resolution and extracts RGB data
 * optimized for LED strip color mapping.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;
    
    // Constants
    private static final int IMAGE_READER_IMAGES = 2;
    private static final int BORDER_CHECK_FRAMES = 60;
    private static final int BYTES_PER_PIXEL_RGBA = 4;
    private static final int BYTES_PER_PIXEL_RGB = 3;
    
    // Capture components
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    
    // State
    private volatile boolean mRunning;
    private int mCaptureWidth;
    private int mCaptureHeight;
    
    // Pre-allocated buffers (avoid GC pressure)
    private byte[] mRgbBuffer;
    private byte[] mRowBuffer;
    private final byte[] mAvgColorResult = new byte[3];
    
    // Border detection cache
    private int mBorderX;
    private int mBorderY;
    private int mFrameCount;
    
    // Capture loop runnable (single allocation)
    private final Runnable mCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            
            final long start = System.nanoTime();
            captureFrame();
            
            if (mRunning && mCaptureHandler != null) {
                final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                final long delayMs = Math.max(1L, mFrameIntervalMs - elapsedMs);
                mCaptureHandler.postDelayed(this, delayMs);
            }
        }
    };
    
    // Frame interval in milliseconds
    private final long mFrameIntervalMs;
    
    // Display callback (single allocation)
    private final VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d(TAG, "Display paused");
        }

        @Override
        public void onResumed() {
            if (DEBUG) Log.d(TAG, "Display resumed");
            if (!mRunning) startCapture();
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d(TAG, "Display stopped");
            mRunning = false;
            setCapturing(false);
        }
    };

    /**
     * Creates a new screen encoder.
     */
    HyperionScreenEncoder(HyperionThread.HyperionThreadListener listener,
                          MediaProjection projection,
                          int screenWidth, int screenHeight,
                          int density,
                          HyperionGrabberOptions options) {
        super(listener, projection, screenWidth, screenHeight, density, options);
        
        mFrameIntervalMs = 1000L / mFrameRate;
        initCaptureDimensions();
        
        if (DEBUG) Log.d(TAG, "Capture: " + mCaptureWidth + "x" + mCaptureHeight + " @ " + mFrameRate + "fps");
        
        try {
            init();
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Init failed", e);
        }
    }
    
    /**
     * Calculates optimal capture dimensions based on LED count.
     * Ensures even dimensions for device compatibility.
     */
    private void initCaptureDimensions() {
        // Use LED count directly - no need for higher resolution
        int w = Math.max(4, Math.min(getGrabberWidth(), 128));
        int h = Math.max(4, Math.min(getGrabberHeight(), 72));
        
        // Round down to even numbers
        mCaptureWidth = w & ~1;
        mCaptureHeight = h & ~1;
    }

    /**
     * Initializes capture components.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void init() throws MediaCodec.CodecException {
        // Background thread for capture
        mCaptureThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        // Image reader
        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);

        // Projection callback
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, mHandler);

        // Virtual display
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                mDisplayCallback,
                mHandler);

        startCapture();
    }

    /**
     * Starts the capture loop.
     */
    private void startCapture() {
        mRunning = true;
        setCapturing(true);
        mFrameCount = 0;
        mCaptureHandler.post(mCaptureRunnable);
    }
    
    /**
     * Captures and processes a single frame.
     */
    private void captureFrame() {
        Image img = null;
        try {
            img = mImageReader.acquireLatestImage();
            if (img != null) {
                processImage(img);
            }
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Capture error", e);
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /**
     * Processes captured image and sends to Hyperion.
     */
    private void processImage(Image img) {
        final Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return;
        
        final Image.Plane plane = planes[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        
        // Update border detection periodically
        updateBorderDetection(buffer, width, height, rowStride, pixelStride);
        
        // Send frame data
        if (mAvgColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride);
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride);
        }
    }
    
    /**
     * Updates border detection every N frames.
     */
    private void updateBorderDetection(ByteBuffer buffer, int width, int height, 
                                        int rowStride, int pixelStride) {
        if (!mRemoveBorders && !mAvgColor) return;
        
        if (++mFrameCount >= BORDER_CHECK_FRAMES) {
            mFrameCount = 0;
            mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride);
            final BorderProcessor.BorderObject border = mBorderProcessor.getCurrentBorder();
            if (border != null && border.isKnown()) {
                mBorderX = border.getHorizontalBorderIndex();
                mBorderY = border.getVerticalBorderIndex();
            }
        }
    }
    
    /**
     * Extracts and sends RGB pixel data.
     */
    private void sendPixelData(ByteBuffer buffer, int width, int height,
                               int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int effWidth = width - (bx << 1);
        final int effHeight = height - (by << 1);
        
        if (effWidth <= 0 || effHeight <= 0) return;
        
        final byte[] rgb = extractRgb(buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight);
        mListener.sendFrame(rgb, effWidth, effHeight);
    }
    
    /**
     * Extracts RGB data from RGBA buffer.
     * Optimized for the common case where pixelStride=4 and rowStride=width*4.
     */
    private byte[] extractRgb(ByteBuffer buffer, int width, int height,
                              int rowStride, int pixelStride,
                              int bx, int by, int effWidth, int effHeight) {
        final int rgbSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;
        
        // Ensure buffer capacity
        if (mRgbBuffer == null || mRgbBuffer.length < rgbSize) {
            mRgbBuffer = new byte[rgbSize];
        }
        
        final int endY = height - by;
        final int endX = width - bx;
        int rgbIdx = 0;
        
        // Fast path: standard memory layout
        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            final int rowBytes = effWidth * BYTES_PER_PIXEL_RGBA;
            
            if (mRowBuffer == null || mRowBuffer.length < rowBytes) {
                mRowBuffer = new byte[rowBytes];
            }
            
            final int savedPos = buffer.position();
            
            for (int y = by; y < endY; y++) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA);
                buffer.get(mRowBuffer, 0, rowBytes);
                
                // Unrolled loop: process 4 pixels at a time when possible
                int i = 0;
                final int unrollLimit = rowBytes - 15;
                for (; i < unrollLimit; i += 16) {
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 1];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 2];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 4];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 5];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 6];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 8];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 9];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 10];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 12];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 13];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 14];
                }
                // Handle remaining pixels
                for (; i < rowBytes; i += BYTES_PER_PIXEL_RGBA) {
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 1];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 2];
                }
            }
            
            buffer.position(savedPos);
        } else {
            // Slow path: non-standard layout
            for (int y = by; y < endY; y++) {
                final int rowOff = y * rowStride;
                for (int x = bx; x < endX; x++) {
                    final int off = rowOff + x * pixelStride;
                    mRgbBuffer[rgbIdx++] = buffer.get(off);
                    mRgbBuffer[rgbIdx++] = buffer.get(off + 1);
                    mRgbBuffer[rgbIdx++] = buffer.get(off + 2);
                }
            }
        }
        
        return mRgbBuffer;
    }
    
    /**
     * Calculates and sends average color.
     * Samples every 4th pixel in both dimensions (1/16 of pixels).
     */
    private void sendAverageColor(ByteBuffer buffer, int width, int height,
                                  int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int startX = bx;
        final int startY = by;
        final int endX = width - bx;
        final int endY = height - by;
        
        if (endX <= startX || endY <= startY) return;
        
        long r = 0, g = 0, b = 0;
        int count = 0;
        
        // Sample every 4th pixel
        for (int y = startY; y < endY; y += 4) {
            final int rowOff = y * rowStride;
            for (int x = startX; x < endX; x += 4) {
                final int off = rowOff + x * pixelStride;
                r += buffer.get(off) & 0xFF;
                g += buffer.get(off + 1) & 0xFF;
                b += buffer.get(off + 2) & 0xFF;
                count++;
            }
        }
        
        if (count > 0) {
            mAvgColorResult[0] = (byte) (r / count);
            mAvgColorResult[1] = (byte) (g / count);
            mAvgColorResult[2] = (byte) (b / count);
            mListener.sendFrame(mAvgColorResult, 1, 1);
        }
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "Stopping");
        mRunning = false;
        setCapturing(false);
        
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
            mCaptureHandler = null;
        }
        
        mRgbBuffer = null;
        mRowBuffer = null;
        mBorderX = 0;
        mBorderY = 0;
        mFrameCount = 0;
        
        mHandler.getLooper().quit();
        clearAndDisconnect();
        
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "Resuming");
        if (!isCapturing() && mImageReader != null) {
            startCapture();
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void setOrientation(int orientation) {
        if (mVirtualDisplay == null || orientation == mCurrentOrientation) return;
        
        mCurrentOrientation = orientation;
        mRunning = false;
        setCapturing(false);
        
        // Swap dimensions
        final int tmp = mCaptureWidth;
        mCaptureWidth = mCaptureHeight;
        mCaptureHeight = tmp;
        
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        
        mVirtualDisplay.resize(mCaptureWidth, mCaptureHeight, mDensity);
        
        if (mImageReader != null) {
            mImageReader.close();
        }
        
        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);
        
        mVirtualDisplay.setSurface(mImageReader.getSurface());
        
        // Clear buffers for new dimensions
        mRgbBuffer = null;
        mRowBuffer = null;
        
        startCapture();
    }

    @Override
    public void clearLights() {
        super.clearLights();
    }
}
