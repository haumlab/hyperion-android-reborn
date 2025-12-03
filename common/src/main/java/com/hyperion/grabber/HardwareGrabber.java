package com.hyperion.grabber.common;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;

/**
 * Hardware-specific video grabber that supports multiple SoC vendors:
 * - Amlogic (S905, S912, S922, etc.) - Common in Android TV boxes
 * - MediaTek (MT8167, MT8173, MT8695, etc.) - Common in smart TVs and streaming devices
 * - Rockchip (RK3328, RK3399, etc.) - Common in Android TV boxes
 * 
 * These grabbers capture directly from the video processing unit, bypassing
 * Android's compositor and avoiding HDR tonemapping overhead.
 * 
 * Requires root access on most devices.
 */
public class HardwareGrabber {
    private static final String TAG = "HardwareGrabber";
    
    // SoC Types
    public enum SocType {
        UNKNOWN,
        AMLOGIC,
        MEDIATEK,
        ROCKCHIP
    }
    
    // ==================== AMLOGIC PATHS ====================
    private static final String AML_VIDEOCAP_DEV = "/dev/amvideocap0";
    private static final String AML_GE2D_DEV = "/dev/ge2d";
    private static final String AML_VIDEO_WIDTH = "/sys/class/video/frame_width";
    private static final String AML_VIDEO_HEIGHT = "/sys/class/video/frame_height";
    private static final String AML_DISPLAY_MODE = "/sys/class/display/mode";
    
    // ==================== MEDIATEK PATHS ====================
    // MediaTek uses different paths depending on chip generation
    private static final String MTK_VIDEOCAP_DEV = "/dev/mtk_disp_mgr";
    private static final String MTK_VIDEOCAP_DEV2 = "/dev/video0";
    private static final String MTK_MDP_DEV = "/dev/mdp_sync";
    private static final String MTK_OVL_DEV = "/dev/mtk_ovl";
    private static final String MTK_DISP_DEV = "/dev/graphics/fb0";
    // MediaTek sysfs paths
    private static final String MTK_VIDEO_INFO = "/sys/class/video/video_state";
    private static final String MTK_HDMI_INFO = "/sys/class/switch/hdmi/state";
    private static final String MTK_DISPLAY_INFO = "/sys/kernel/debug/mtkfb";
    
    // ==================== ROCKCHIP PATHS ====================
    private static final String RK_VIDEO_DEV = "/dev/video0";
    private static final String RK_VPU_DEV = "/dev/vpu_service";
    private static final String RK_RGA_DEV = "/dev/rga";
    
    // Capture configuration
    private int mCaptureWidth = 64;
    private int mCaptureHeight = 36;
    private boolean mIsAvailable = false;
    private SocType mSocType = SocType.UNKNOWN;
    private String mActiveDevice = null;
    
    public HardwareGrabber() {
        detectSoC();
    }
    
    public HardwareGrabber(int width, int height) {
        mCaptureWidth = width;
        mCaptureHeight = height;
        detectSoC();
    }
    
    /**
     * Detect which SoC type is present and which capture device to use
     */
    private void detectSoC() {
        // Check for Amlogic first (most common in Android TV boxes)
        if (checkAmlogic()) {
            mSocType = SocType.AMLOGIC;
            mIsAvailable = true;
            Log.i(TAG, "Detected Amlogic SoC, using: " + mActiveDevice);
            return;
        }
        
        // Check for MediaTek
        if (checkMediaTek()) {
            mSocType = SocType.MEDIATEK;
            mIsAvailable = true;
            Log.i(TAG, "Detected MediaTek SoC, using: " + mActiveDevice);
            return;
        }
        
        // Check for Rockchip
        if (checkRockchip()) {
            mSocType = SocType.ROCKCHIP;
            mIsAvailable = true;
            Log.i(TAG, "Detected Rockchip SoC, using: " + mActiveDevice);
            return;
        }
        
        // Check CPU info as fallback
        String cpuInfo = getCpuInfo();
        if (cpuInfo.toLowerCase().contains("amlogic") || cpuInfo.contains("meson")) {
            mSocType = SocType.AMLOGIC;
            Log.i(TAG, "Detected Amlogic via CPU info");
        } else if (cpuInfo.toLowerCase().contains("mediatek") || cpuInfo.toLowerCase().contains("mt8")) {
            mSocType = SocType.MEDIATEK;
            Log.i(TAG, "Detected MediaTek via CPU info");
        } else if (cpuInfo.toLowerCase().contains("rockchip") || cpuInfo.toLowerCase().contains("rk3")) {
            mSocType = SocType.ROCKCHIP;
            Log.i(TAG, "Detected Rockchip via CPU info");
        }
        
        mIsAvailable = false;
        Log.w(TAG, "No hardware video capture available");
    }
    
    private boolean checkAmlogic() {
        File videocap = new File(AML_VIDEOCAP_DEV);
        if (videocap.exists()) {
            mActiveDevice = AML_VIDEOCAP_DEV;
            return true;
        }
        
        File ge2d = new File(AML_GE2D_DEV);
        if (ge2d.exists()) {
            mActiveDevice = AML_GE2D_DEV;
            return true;
        }
        
        return false;
    }
    
    private boolean checkMediaTek() {
        // Check primary MediaTek capture devices
        File mtkDisp = new File(MTK_VIDEOCAP_DEV);
        if (mtkDisp.exists()) {
            mActiveDevice = MTK_VIDEOCAP_DEV;
            return true;
        }
        
        File mtkMdp = new File(MTK_MDP_DEV);
        if (mtkMdp.exists()) {
            mActiveDevice = MTK_MDP_DEV;
            return true;
        }
        
        File mtkOvl = new File(MTK_OVL_DEV);
        if (mtkOvl.exists()) {
            mActiveDevice = MTK_OVL_DEV;
            return true;
        }
        
        // Check for MediaTek via sysfs
        File mtkDebug = new File(MTK_DISPLAY_INFO);
        if (mtkDebug.exists()) {
            mActiveDevice = MTK_DISP_DEV;
            return true;
        }
        
        // Check video device (common on MediaTek smart TVs)
        File video0 = new File(MTK_VIDEOCAP_DEV2);
        if (video0.exists() && isMediatekVideo0()) {
            mActiveDevice = MTK_VIDEOCAP_DEV2;
            return true;
        }
        
        return false;
    }
    
    private boolean checkRockchip() {
        File vpu = new File(RK_VPU_DEV);
        if (vpu.exists()) {
            mActiveDevice = RK_VIDEO_DEV;
            return true;
        }
        
        File rga = new File(RK_RGA_DEV);
        if (rga.exists()) {
            mActiveDevice = RK_VIDEO_DEV;
            return true;
        }
        
        return false;
    }
    
    private boolean isMediatekVideo0() {
        // Try to determine if /dev/video0 is a MediaTek device
        try {
            String cpuInfo = getCpuInfo();
            return cpuInfo.toLowerCase().contains("mediatek") || 
                   cpuInfo.toLowerCase().contains("mt8");
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getCpuInfo() {
        try {
            return readStringFromFile("/proc/cpuinfo");
        } catch (Exception e) {
            return "";
        }
    }
    
    public boolean isAvailable() {
        return mIsAvailable;
    }
    
    public SocType getSocType() {
        return mSocType;
    }
    
    public String getActiveDevice() {
        return mActiveDevice;
    }
    
    /**
     * Capture a frame from the video decoder
     */
    public byte[] captureFrame() {
        if (!mIsAvailable) {
            return null;
        }
        
        switch (mSocType) {
            case AMLOGIC:
                return captureAmlogic();
            case MEDIATEK:
                return captureMediaTek();
            case ROCKCHIP:
                return captureRockchip();
            default:
                return null;
        }
    }
    
    // ==================== AMLOGIC CAPTURE ====================
    private byte[] captureAmlogic() {
        if (AML_VIDEOCAP_DEV.equals(mActiveDevice)) {
            return captureAmlogicVideocap();
        } else {
            return captureFramebuffer(AML_GE2D_DEV);
        }
    }
    
    private byte[] captureAmlogicVideocap() {
        RandomAccessFile capDevice = null;
        try {
            capDevice = new RandomAccessFile(AML_VIDEOCAP_DEV, "rw");
            
            int bufferSize = mCaptureWidth * mCaptureHeight * 3;
            byte[] buffer = new byte[bufferSize];
            
            int bytesRead = capDevice.read(buffer);
            
            if (bytesRead == bufferSize) {
                return buffer;
            } else if (bytesRead > 0) {
                byte[] partial = new byte[bytesRead];
                System.arraycopy(buffer, 0, partial, 0, bytesRead);
                return partial;
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Amlogic videocap failed: " + e.getMessage());
            return null;
        } finally {
            closeQuietly(capDevice);
        }
    }
    
    // ==================== MEDIATEK CAPTURE ====================
    private byte[] captureMediaTek() {
        // MediaTek capture strategy depends on the device type
        if (MTK_VIDEOCAP_DEV.equals(mActiveDevice)) {
            return captureMtkDispMgr();
        } else if (MTK_MDP_DEV.equals(mActiveDevice)) {
            return captureMtkMdp();
        } else if (MTK_OVL_DEV.equals(mActiveDevice)) {
            return captureMtkOvl();
        } else if (MTK_VIDEOCAP_DEV2.equals(mActiveDevice)) {
            return captureMtkVideo0();
        } else {
            return captureFramebuffer(MTK_DISP_DEV);
        }
    }
    
    private byte[] captureMtkDispMgr() {
        // mtk_disp_mgr provides display capture functionality
        RandomAccessFile capDevice = null;
        try {
            capDevice = new RandomAccessFile(MTK_VIDEOCAP_DEV, "rw");
            
            // MediaTek display manager capture
            int bufferSize = mCaptureWidth * mCaptureHeight * 4; // BGRA
            byte[] buffer = new byte[bufferSize];
            
            int bytesRead = capDevice.read(buffer);
            
            if (bytesRead > 0) {
                return convertBGRAtoRGB(buffer, bytesRead);
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "MTK disp_mgr capture failed: " + e.getMessage());
            return captureFramebuffer(MTK_DISP_DEV);
        } finally {
            closeQuietly(capDevice);
        }
    }
    
    private byte[] captureMtkMdp() {
        // MediaTek MDP (Media Data Path) provides video processing access
        try {
            // MDP requires ioctl setup - fall back to framebuffer
            return captureFramebuffer(MTK_DISP_DEV);
        } catch (Exception e) {
            Log.w(TAG, "MTK MDP capture failed: " + e.getMessage());
            return null;
        }
    }
    
    private byte[] captureMtkOvl() {
        // MediaTek OVL (Overlay) provides layer composition access
        try {
            // OVL requires ioctl setup - fall back to framebuffer
            return captureFramebuffer(MTK_DISP_DEV);
        } catch (Exception e) {
            Log.w(TAG, "MTK OVL capture failed: " + e.getMessage());
            return null;
        }
    }
    
    private byte[] captureMtkVideo0() {
        // V4L2 capture via /dev/video0
        RandomAccessFile capDevice = null;
        try {
            capDevice = new RandomAccessFile(MTK_VIDEOCAP_DEV2, "rw");
            
            int bufferSize = mCaptureWidth * mCaptureHeight * 2; // YUV422
            byte[] buffer = new byte[bufferSize];
            
            int bytesRead = capDevice.read(buffer);
            
            if (bytesRead > 0) {
                return convertYUV422toRGB(buffer, bytesRead);
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "MTK Video0 capture failed: " + e.getMessage());
            return captureFramebuffer(MTK_DISP_DEV);
        } finally {
            closeQuietly(capDevice);
        }
    }
    
    // ==================== ROCKCHIP CAPTURE ====================
    private byte[] captureRockchip() {
        // Rockchip typically uses V4L2 interface
        RandomAccessFile capDevice = null;
        try {
            capDevice = new RandomAccessFile(RK_VIDEO_DEV, "rw");
            
            int bufferSize = mCaptureWidth * mCaptureHeight * 2; // NV12/NV21
            byte[] buffer = new byte[bufferSize];
            
            int bytesRead = capDevice.read(buffer);
            
            if (bytesRead > 0) {
                return convertNV12toRGB(buffer, mCaptureWidth, mCaptureHeight);
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Rockchip capture failed: " + e.getMessage());
            return captureFramebuffer("/dev/graphics/fb0");
        } finally {
            closeQuietly(capDevice);
        }
    }
    
    // ==================== FRAMEBUFFER FALLBACK ====================
    private byte[] captureFramebuffer(String fbPath) {
        FileInputStream fis = null;
        try {
            File fb = new File(fbPath);
            if (!fb.exists()) {
                fb = new File("/dev/graphics/fb0");
            }
            if (!fb.exists()) {
                return null;
            }
            
            fis = new FileInputStream(fb);
            byte[] buffer = new byte[mCaptureWidth * mCaptureHeight * 4];
            int bytesRead = fis.read(buffer);
            
            if (bytesRead > 0) {
                return convertRGBAtoRGB(buffer);
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Framebuffer capture failed: " + e.getMessage());
            return null;
        } finally {
            closeQuietly(fis);
        }
    }
    
    // ==================== COLOR CONVERSION ====================
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
    
    private byte[] convertBGRAtoRGB(byte[] bgra, int length) {
        int pixels = length / 4;
        byte[] rgb = new byte[pixels * 3];
        
        for (int i = 0, j = 0; i < length - 3 && j < rgb.length - 2; i += 4, j += 3) {
            rgb[j] = bgra[i + 2];     // R (from B position)
            rgb[j + 1] = bgra[i + 1]; // G
            rgb[j + 2] = bgra[i];     // B (from R position)
        }
        
        return rgb;
    }
    
    private byte[] convertYUV422toRGB(byte[] yuv, int length) {
        // YUYV format: Y0 U0 Y1 V0
        int pixels = length / 2;
        byte[] rgb = new byte[pixels * 3];
        
        for (int i = 0, j = 0; i < length - 3 && j < rgb.length - 5; i += 4, j += 6) {
            int y0 = yuv[i] & 0xFF;
            int u = yuv[i + 1] & 0xFF;
            int y1 = yuv[i + 2] & 0xFF;
            int v = yuv[i + 3] & 0xFF;
            
            // Convert YUV to RGB for first pixel
            int[] rgb0 = yuvToRgb(y0, u, v);
            rgb[j] = (byte) rgb0[0];
            rgb[j + 1] = (byte) rgb0[1];
            rgb[j + 2] = (byte) rgb0[2];
            
            // Convert YUV to RGB for second pixel
            int[] rgb1 = yuvToRgb(y1, u, v);
            rgb[j + 3] = (byte) rgb1[0];
            rgb[j + 4] = (byte) rgb1[1];
            rgb[j + 5] = (byte) rgb1[2];
        }
        
        return rgb;
    }
    
    private byte[] convertNV12toRGB(byte[] nv12, int width, int height) {
        // NV12: Y plane followed by interleaved UV plane
        int frameSize = width * height;
        byte[] rgb = new byte[frameSize * 3];
        
        int uvOffset = frameSize;
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yIndex = y * width + x;
                int uvIndex = uvOffset + (y / 2) * width + (x & ~1);
                
                if (yIndex >= frameSize || uvIndex + 1 >= nv12.length) break;
                
                int yVal = nv12[yIndex] & 0xFF;
                int uVal = nv12[uvIndex] & 0xFF;
                int vVal = nv12[uvIndex + 1] & 0xFF;
                
                int[] rgbVal = yuvToRgb(yVal, uVal, vVal);
                int rgbIndex = yIndex * 3;
                
                if (rgbIndex + 2 < rgb.length) {
                    rgb[rgbIndex] = (byte) rgbVal[0];
                    rgb[rgbIndex + 1] = (byte) rgbVal[1];
                    rgb[rgbIndex + 2] = (byte) rgbVal[2];
                }
            }
        }
        
        return rgb;
    }
    
    private int[] yuvToRgb(int y, int u, int v) {
        // BT.601 conversion
        int c = y - 16;
        int d = u - 128;
        int e = v - 128;
        
        int r = clamp((298 * c + 409 * e + 128) >> 8);
        int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
        int b = clamp((298 * c + 516 * d + 128) >> 8);
        
        return new int[]{r, g, b};
    }
    
    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
    
    // ==================== UTILITY METHODS ====================
    
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
    
    /**
     * Check if video is currently playing
     */
    public boolean isVideoPlaying() {
        switch (mSocType) {
            case AMLOGIC:
                return isAmlogicVideoPlaying();
            case MEDIATEK:
                return isMediatekVideoPlaying();
            default:
                return false;
        }
    }
    
    private boolean isAmlogicVideoPlaying() {
        try {
            int width = readIntFromFile(AML_VIDEO_WIDTH);
            int height = readIntFromFile(AML_VIDEO_HEIGHT);
            return width > 0 && height > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean isMediatekVideoPlaying() {
        try {
            String state = readStringFromFile(MTK_VIDEO_INFO);
            return state.contains("playing") || state.contains("1");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if display is in HDR mode
     */
    public boolean isHDRMode() {
        try {
            String mode = "";
            switch (mSocType) {
                case AMLOGIC:
                    mode = readStringFromFile(AML_DISPLAY_MODE);
                    break;
                case MEDIATEK:
                    mode = readStringFromFile(MTK_HDMI_INFO);
                    break;
            }
            return mode.toLowerCase().contains("hdr") || 
                   mode.toLowerCase().contains("dv") ||
                   mode.toLowerCase().contains("dolby");
        } catch (Exception e) {
            return false;
        }
    }
    
    private int readIntFromFile(String path) throws Exception {
        String value = readStringFromFile(path);
        if (!value.isEmpty()) {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        }
        return 0;
    }
    
    private String readStringFromFile(String path) throws Exception {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            byte[] buffer = new byte[256];
            int len = fis.read(buffer);
            if (len > 0) {
                return new String(buffer, 0, len).trim();
            }
            return "";
        } finally {
            closeQuietly(fis);
        }
    }
    
    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
    }
}
