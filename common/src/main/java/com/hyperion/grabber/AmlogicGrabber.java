package com.hyperion.grabber.common;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AmlogicGrabber {
    private static final String TAG = "AmlogicGrabber";
    
    // Amlogic device paths
    private static final String AMVIDEO_DEV = "/dev/amvideo";
    private static final String AMVIDEOCAP_DEV = "/dev/amvideocap0";
    private static final String GE2D_DEV = "/dev/ge2d";
    
    // Sysfs paths for video info
    private static final String VIDEO_FRAME_WIDTH = "/sys/class/video/frame_width";
    private static final String VIDEO_FRAME_HEIGHT = "/sys/class/video/frame_height";
    private static final String VIDEO_AXIS = "/sys/class/video/axis";
    private static final String DISPLAY_MODE = "/sys/class/display/mode";
    
    // Capture modes
    private static final int CAPTURE_MODE_GE2D = 0;
    private static final int CAPTURE_MODE_VIDEOCAP = 1;
    
    private int mCaptureWidth = 64;
    private int mCaptureHeight = 64;
    private boolean mIsAvailable = false;
    private int mCaptureMode = CAPTURE_MODE_VIDEOCAP;
    
    public AmlogicGrabber() {
        checkAvailability();
    }
    
    public AmlogicGrabber(int width, int height) {
        mCaptureWidth = width;
        mCaptureHeight = height;
        checkAvailability();
    }
    
    private void checkAvailability() {
        // Check if Amlogic video capture devices exist
        File amvideocap = new File(AMVIDEOCAP_DEV);
        File ge2d = new File(GE2D_DEV);
        
        if (amvideocap.exists()) {
            mIsAvailable = true;
            mCaptureMode = CAPTURE_MODE_VIDEOCAP;
            Log.i(TAG, "Amlogic videocap device found: " + AMVIDEOCAP_DEV);
        } else if (ge2d.exists()) {
            mIsAvailable = true;
            mCaptureMode = CAPTURE_MODE_GE2D;
            Log.i(TAG, "Amlogic GE2D device found: " + GE2D_DEV);
        } else {
            mIsAvailable = false;
            Log.w(TAG, "No Amlogic capture device found");
        }
    }
    
    public boolean isAvailable() {
        return mIsAvailable;
    }
    
    /**
     * Check if video is currently playing
     */
    public boolean isVideoPlaying() {
        try {
            int width = readIntFromFile(VIDEO_FRAME_WIDTH);
            int height = readIntFromFile(VIDEO_FRAME_HEIGHT);
            return width > 0 && height > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get current video dimensions
     */
    public int[] getVideoDimensions() {
        try {
            int width = readIntFromFile(VIDEO_FRAME_WIDTH);
            int height = readIntFromFile(VIDEO_FRAME_HEIGHT);
            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
    
    public byte[] captureFrame() {
        if (!mIsAvailable) {
            return null;
        }
        
        if (mCaptureMode == CAPTURE_MODE_VIDEOCAP) {
            return captureViaVideocap();
        } else {
            return captureViaGe2d();
        }
    }
    
    /**
     * Capture using /dev/amvideocap0 device
     */
    private byte[] captureViaVideocap() {
        RandomAccessFile capDevice = null;
        try {
            capDevice = new RandomAccessFile(AMVIDEOCAP_DEV, "rw");
            
            // Configure capture parameters via ioctl-like writes
            // Format: width height outputFormat
            // The device expects configuration before reading
            
            // Try to read directly - device auto-configures on some firmware versions
            int bufferSize = mCaptureWidth * mCaptureHeight * 3; // RGB24
            byte[] buffer = new byte[bufferSize];
            
            int bytesRead = capDevice.read(buffer);
            
            if (bytesRead == bufferSize) {
                return buffer;
            } else if (bytesRead > 0) {
                // Partial read - try to work with what we got
                byte[] partial = new byte[bytesRead];
                System.arraycopy(buffer, 0, partial, 0, bytesRead);
                return partial;
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Videocap capture failed: " + e.getMessage());
            return null;
        } finally {
            if (capDevice != null) {
                try {
                    capDevice.close();
                } catch (Exception ignored) {}
            }
        }
    }
    
    private byte[] captureViaGe2d() {
        // GE2D capture requires more complex ioctl setup
        // This is a simplified version
        try {
            // Read from canvas device instead
            String canvasPath = "/dev/graphics/fb0";
            File fb = new File(canvasPath);
            if (!fb.exists()) {
                return null;
            }
            
            FileInputStream fis = new FileInputStream(fb);
            byte[] buffer = new byte[mCaptureWidth * mCaptureHeight * 4]; // RGBA
            int bytesRead = fis.read(buffer);
            fis.close();
            
            if (bytesRead > 0) {
                // Convert RGBA to RGB
                return convertRGBAtoRGB(buffer);
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "GE2D capture failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Compute average color from captured frame
     */
    public byte[] captureAverageColor() {
        byte[] frame = captureFrame();
        if (frame == null || frame.length < 3) {
            return new byte[]{0, 0, 0};
        }
        
        long totalR = 0, totalG = 0, totalB = 0;
        int pixelCount = frame.length / 3;
        
        for (int i = 0; i < frame.length - 2; i += 3) {
            totalR += frame[i] & 0xFF;
            totalG += frame[i + 1] & 0xFF;
            totalB += frame[i + 2] & 0xFF;
        }
        
        return new byte[]{
            (byte) (totalR / pixelCount),
            (byte) (totalG / pixelCount),
            (byte) (totalB / pixelCount)
        };
    }
    
    private byte[] convertRGBAtoRGB(byte[] rgba) {
        int pixels = rgba.length / 4;
        byte[] rgb = new byte[pixels * 3];
        
        for (int i = 0, j = 0; i < rgba.length - 3 && j < rgb.length - 2; i += 4, j += 3) {
            rgb[j] = rgba[i];         // R
            rgb[j + 1] = rgba[i + 1]; // G
            rgb[j + 2] = rgba[i + 2]; // B
        }
        
        return rgb;
    }
    
    private int readIntFromFile(String path) throws Exception {
        FileInputStream fis = new FileInputStream(path);
        byte[] buffer = new byte[32];
        int len = fis.read(buffer);
        fis.close();
        
        if (len > 0) {
            String value = new String(buffer, 0, len).trim();
            return Integer.parseInt(value);
        }
        return 0;
    }
    
    private String readStringFromFile(String path) throws Exception {
        FileInputStream fis = new FileInputStream(path);
        byte[] buffer = new byte[256];
        int len = fis.read(buffer);
        fis.close();
        
        if (len > 0) {
            return new String(buffer, 0, len).trim();
        }
        return "";
    }
    
    /**
     * Check if display is in HDR mode
     */
    public boolean isHDRMode() {
        try {
            String mode = readStringFromFile(DISPLAY_MODE);
            return mode.contains("hdr") || mode.contains("dv") || mode.contains("2160p");
        } catch (Exception e) {
            return false;
        }
    }
}
