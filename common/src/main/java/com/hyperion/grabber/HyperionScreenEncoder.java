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
    private volatile boolean mRunning;
    private int mCaptureWidth;
    private int mCaptureHeight;
    private byte[] mRgbBuffer;
    private byte[] mRowBuffer;
    private final byte[] mAvgColorResult = new byte[3];
    private int mBorderX;
    private int mBorderY;
    private int mFrameCount;
    
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
    
    private final long mFrameIntervalMs;
    
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
    
    private void initCaptureDimensions() {
        int w = Math.max(4, Math.min(getGrabberWidth(), 128));
        int h = Math.max(4, Math.min(getGrabberHeight(), 72));
        mCaptureWidth = w & ~1;
        mCaptureHeight = h & ~1;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void init() throws MediaCodec.CodecException {
        mCaptureThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                mDisplayCallback,
                mHandler);

        startCapture();
    }

    private void startCapture() {
        mRunning = true;
        setCapturing(true);
        mFrameCount = 0;
        mCaptureHandler.post(mCaptureRunnable);
    }
    
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

    private void processImage(Image img) {
        final Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return;
        
        final Image.Plane plane = planes[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        
        updateBorderDetection(buffer, width, height, rowStride, pixelStride);
        
        if (mAvgColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride);
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride);
        }
    }
    
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
    
    private byte[] extractRgb(ByteBuffer buffer, int width, int height,
                              int rowStride, int pixelStride,
                              int bx, int by, int effWidth, int effHeight) {
        final int rgbSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;
        
        if (mRgbBuffer == null || mRgbBuffer.length < rgbSize) {
            mRgbBuffer = new byte[rgbSize];
        }
        
        final int endY = height - by;
        final int endX = width - bx;
        int rgbIdx = 0;
        
        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            final int rowBytes = effWidth * BYTES_PER_PIXEL_RGBA;
            
            if (mRowBuffer == null || mRowBuffer.length < rowBytes) {
                mRowBuffer = new byte[rowBytes];
            }
            
            final int savedPos = buffer.position();
            
            for (int y = by; y < endY; y++) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA);
                buffer.get(mRowBuffer, 0, rowBytes);
                
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
                for (; i < rowBytes; i += BYTES_PER_PIXEL_RGBA) {
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 1];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 2];
                }
            }
            
            buffer.position(savedPos);
        } else {
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
        
        mRgbBuffer = null;
        mRowBuffer = null;
        
        startCapture();
    }

    @Override
    public void clearLights() {
        super.clearLights();
    }
}
