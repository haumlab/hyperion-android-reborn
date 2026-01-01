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

public class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;
    
    private static final int MAX_IMAGE_READER_IMAGES = 2;
    
    private static final int MAX_CAPTURE_WIDTH = 1280;
    private static final int MAX_CAPTURE_HEIGHT = 720;
    
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private volatile boolean mRunning = false;
    
    private int mCaptureWidth;
    private int mCaptureHeight;
    private volatile byte[] mLastFrame;
    private int mLastWidth;
    private int mLastHeight;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    HyperionScreenEncoder(final HyperionThread.HyperionThreadListener listener,
                           final MediaProjection projection, final int width, final int height,
                           final int density, HyperionGrabberOptions options) {
        super(listener, projection, width, height, density, options);
        
        // Calculate capture dimensions maintaining aspect ratio
        // Use higher resolution for HDR tone mapping, lower for regular mode
        boolean useHdrResolution = options.useHdrToneMapping();
        int maxWidth = useHdrResolution ? 1280 : 128;
        int maxHeight = useHdrResolution ? 720 : 72;
        
        float aspectRatio = (float) width / height;
        if (aspectRatio >= 1.0f) {
            // Landscape
            mCaptureWidth = Math.min(maxWidth, getGrabberWidth());
            mCaptureHeight = Math.max(1, (int) (mCaptureWidth / aspectRatio));
        } else {
            // Portrait
            mCaptureHeight = Math.min(maxHeight, getGrabberHeight());
            mCaptureWidth = Math.max(1, (int) (mCaptureHeight * aspectRatio));
        }
        
        // Ensure dimensions are even (required by some devices)
        mCaptureWidth = (mCaptureWidth / 2) * 2;
        mCaptureHeight = (mCaptureHeight / 2) * 2;
        if (mCaptureWidth < 2) mCaptureWidth = 2;
        if (mCaptureHeight < 2) mCaptureHeight = 2;

        if (DEBUG) {
            Log.d(TAG, "Capture dimensions: " + mCaptureWidth + "x" + mCaptureHeight);
        }

        try {
            prepare();
        } catch (MediaCodec.CodecException e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void prepare() throws MediaCodec.CodecException {
        if (DEBUG) Log.d(TAG, "Preparing encoder");

        mCaptureThread = new HandlerThread(TAG + "-Capture", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

        mImageReader = ImageReader.newInstance(
                mCaptureWidth,
                mCaptureHeight,
                PixelFormat.RGBA_8888,
                MAX_IMAGE_READER_IMAGES
        );

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopRecording();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                displayFlags,
                mImageReader.getSurface(),
                mDisplayCallback,
                mHandler);

        startCaptureLoop();
    }

    private void startCaptureLoop() {
        mRunning = true;
        setCapturing(true);
        
        final long frameIntervalNs = 1_000_000_000L / mFrameRate;
        
        mCaptureHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mRunning || !isCapturing()) {
                    return;
                }
                
                long startTime = System.nanoTime();
                
                Image img = null;
                try {
                    img = mImageReader.acquireLatestImage();
                    if (img != null) {
                        sendImage(img);
                    } else {
                        byte[] lastFrame = mLastFrame;
                        if (lastFrame != null) {
                            mListener.sendFrame(lastFrame, mLastWidth, mLastHeight);
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG) Log.w(TAG, "Frame capture error: " + e.getMessage());
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Exception ignored) {}
                    }
                }
                
                long elapsed = System.nanoTime() - startTime;
                long delayMs = Math.max(1, (frameIntervalNs - elapsed) / 1_000_000L);
                
                if (mRunning && mCaptureHandler != null) {
                    mCaptureHandler.postDelayed(this, delayMs);
                }
            }
        });
    }

    @Override
    public void clearLights() {
        super.clearLights();
        mLastFrame = null;
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording Called");
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
        
        mLastFrame = null;
        mHandler.getLooper().quit();
        clearAndDisconnect();
        
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording Called");
        if (!isCapturing() && mImageReader != null) {
            startCaptureLoop();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void setOrientation(int orientation) {
        if (mVirtualDisplay != null && orientation != mCurrentOrientation) {
            mCurrentOrientation = orientation;
            
            int temp = mCaptureWidth;
            mCaptureWidth = mCaptureHeight;
            mCaptureHeight = temp;
            
            mRunning = false;
            setCapturing(false);
            
            if (mCaptureHandler != null) {
                mCaptureHandler.removeCallbacksAndMessages(null);
            }
            
            mVirtualDisplay.resize(mCaptureWidth, mCaptureHeight, mDensity);
            
            if (mImageReader != null) {
                mImageReader.close();
            }
            
            mImageReader = ImageReader.newInstance(
                    mCaptureWidth,
                    mCaptureHeight,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGE_READER_IMAGES
            );
            
            mVirtualDisplay.setSurface(mImageReader.getSurface());
            startCaptureLoop();
        }
    }

    private VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d(TAG, "VirtualDisplay paused");
        }

        @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public void onResumed() {
            if (DEBUG) Log.d(TAG, "VirtualDisplay resumed");
            if (!mRunning) {
                startCaptureLoop();
            }
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d(TAG, "VirtualDisplay stopped");
            mRunning = false;
            setCapturing(false);
        }
    };

    private byte[] getPixels(ByteBuffer buffer, int width, int height, int rowStride,
                             int pixelStride, int firstX, int firstY) {
        int effectiveWidth = width - firstX * 2;
        int effectiveHeight = height - firstY * 2;
        
        if (effectiveWidth <= 0 || effectiveHeight <= 0) {
            effectiveWidth = width;
            effectiveHeight = height;
            firstX = 0;
            firstY = 0;
        }
        
        byte[] pixels = new byte[effectiveWidth * effectiveHeight * 3];
        int pixelIndex = 0;
        
        for (int y = firstY; y < height - firstY; y++) {
            int rowOffset = y * rowStride;
            for (int x = firstX; x < width - firstX; x++) {
                int offset = rowOffset + x * pixelStride;
                pixels[pixelIndex++] = (byte) (buffer.get(offset) & 0xff);     // R
                pixels[pixelIndex++] = (byte) (buffer.get(offset + 1) & 0xff); // G
                pixels[pixelIndex++] = (byte) (buffer.get(offset + 2) & 0xff); // B
            }
        }

        return pixels;
    }

    private byte[] getAverageColor(ByteBuffer buffer, int width, int height, int rowStride,
                                   int pixelStride, int firstX, int firstY) {
        long totalRed = 0, totalGreen = 0, totalBlue = 0;
        int pixelCount = 0;
        
        int startY = Math.max(0, firstY);
        int endY = Math.min(height, height - firstY);
        int startX = Math.max(0, firstX);
        int endX = Math.min(width, width - firstX);

        for (int y = startY; y < endY; y++) {
            int rowOffset = y * rowStride;
            for (int x = startX; x < endX; x++) {
                int offset = rowOffset + x * pixelStride;
                totalRed += buffer.get(offset) & 0xff;
                totalGreen += buffer.get(offset + 1) & 0xff;
                totalBlue += buffer.get(offset + 2) & 0xff;
                pixelCount++;
            }
        }

        byte[] avgColor = new byte[3];
        if (pixelCount > 0) {
            avgColor[0] = (byte) (totalRed / pixelCount);
            avgColor[1] = (byte) (totalGreen / pixelCount);
            avgColor[2] = (byte) (totalBlue / pixelCount);
        }

        return avgColor;
    }

    private void sendImage(Image img) {
        if (img == null) return;
        
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length == 0) return;
        
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        if (buffer == null) return;

        int width = img.getWidth();
        int height = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int firstX = 0;
        int firstY = 0;

        if (mRemoveBorders || mAvgColor) {
            mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride);
            BorderProcessor.BorderObject border = mBorderProcessor.getCurrentBorder();
            if (border != null && border.isKnown()) {
                firstX = border.getHorizontalBorderIndex();
                firstY = border.getVerticalBorderIndex();
            }
        }

        try {
            if (mAvgColor) {
                byte[] avgColor = getAverageColor(buffer, width, height, rowStride, pixelStride, firstX, firstY);
                mLastWidth = 1;
                mLastHeight = 1;
                mLastFrame = avgColor;
                mListener.sendFrame(mLastFrame, 1, 1);
            } else {
                int effectiveWidth = width - firstX * 2;
                int effectiveHeight = height - firstY * 2;
                if (effectiveWidth > 0 && effectiveHeight > 0) {
                    byte[] pixels = getPixels(buffer, width, height, rowStride, pixelStride, firstX, firstY);
                    mLastWidth = effectiveWidth;
                    mLastHeight = effectiveHeight;
                    mLastFrame = pixels;
                    mListener.sendFrame(mLastFrame, effectiveWidth, effectiveHeight);
                }
            }
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Error sending frame: " + e.getMessage());
        }
    }
}
