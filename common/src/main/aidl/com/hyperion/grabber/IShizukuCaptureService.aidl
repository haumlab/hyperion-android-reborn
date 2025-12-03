// IShizukuCaptureService.aidl
package com.hyperion.grabber;

// AIDL interface for Shizuku UserService
// This runs in Shizuku's privileged process with shell (ADB) permissions
// allowing us to access SurfaceControl APIs that require system permissions

interface IShizukuCaptureService {
    // Initialize and check if SurfaceControl works
    boolean initialize();
    
    // Get display token string (for debugging)
    String getDisplayToken();
    
    // Create virtual display for capture
    boolean createDisplay(int width, int height);
    
    // Capture a frame and return RGB data
    byte[] captureFrame();
    
    // Release resources
    void release();
    
    // Check if currently capturing
    boolean isCapturing();
}
