package com.hyperion.grabber.common.network;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hyperion.grabber.common.util.LedDataExtractor;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.common.R;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

public class AdalightClient implements HyperionClient {
    private static final String TAG = "AdalightClient";
    
    public enum ProtocolType {
        ADA,    // Standard Adalight
        LBAPA,  // LightBerry APA102
        AWA     // Hyperserial
    }
    
    private final Context mContext;
    private final int mPriority;
    private final int mBaudRate;
    private final ProtocolType mProtocol = ProtocolType.ADA; // Default to ADA
    private int mXLed;
    private int mYLed;
    
    private UsbSerialPort mPort;
    private volatile boolean mConnected = false;
    
    private final ColorSmoothing mSmoothing;
    private ColorRgb[] mLedDataBuffer;
    
    public AdalightClient(Context context, int priority, int baudRate) throws IOException {
        mContext = context;
        mPriority = priority;
        mBaudRate = baudRate > 0 ? baudRate : 115200; // Default baud rate
        
        // Initialize smoothing with callback to send data
        mSmoothing = new ColorSmoothing(this::sendLedData);
        // Configure smoothing for Ambilight (Low Latency)
        mSmoothing.setSettlingTime(100); // Fast transition (100ms)
        mSmoothing.setOutputDelay(0);    // No buffering delay
        mSmoothing.setUpdateFrequency(40); // 40Hz update rate
        
        connect();
    }
    
    private void connect() throws IOException {
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            throw new IOException("USB service not available on this device");
        }
        
        // Find all available USB serial devices
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            throw new IOException("No USB serial devices found. Please connect your Adalight device via USB OTG cable");
        }
        
        // Log all found devices for debugging
        Log.d(TAG, "Found " + availableDrivers.size() + " USB serial device(s)");
        for (int i = 0; i < availableDrivers.size(); i++) {
            UsbDevice dev = availableDrivers.get(i).getDevice();
            Log.d(TAG, "Device " + i + ": VID=" + dev.getVendorId() + " PID=" + dev.getProductId() + 
                  " Name=" + dev.getDeviceName());
        }
        
        // Use the first available device
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();
        
        // Check if we have permission
        if (!usbManager.hasPermission(device)) {
            // Request permission from user
            Log.i(TAG, "USB permission not granted, requesting permission...");
            requestUsbPermission(usbManager, device);
            
            // Wait a bit for permission dialog
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Check again after request
            if (!usbManager.hasPermission(device)) {
                throw new IOException("USB device permission denied. Please allow USB access when prompted, or grant permission manually in Android Settings > Apps > Hyperion Grabber > Permissions");
            }
        }
        
        // Open the port
        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            throw new IOException("No serial ports available on USB device");
        }
        
        mPort = ports.get(0);
        
        // Try to open device connection
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IOException("Failed to open USB device. Please check USB connection and try again");
        }
        
        try {
            mPort.open(connection);
            mPort.setParameters(mBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            mConnected = true;
            mSmoothing.start();
            Log.i(TAG, "Successfully connected to Adalight device at " + mBaudRate + " baud (VID=" + 
                  device.getVendorId() + " PID=" + device.getProductId() + ")");
        } catch (Exception e) {
            mConnected = false;
            throw new IOException("Failed to configure USB serial port: " + e.getMessage() + 
                  ". Try different baud rate or check device compatibility", e);
        }
    }
    
    /**
     * Request USB permission from user
     */
    private void requestUsbPermission(UsbManager usbManager, UsbDevice device) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 
                       PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                mContext, 
                0, 
                new Intent("com.hyperion.grabber.USB_PERMISSION"), 
                flags
            );
            usbManager.requestPermission(device, permissionIntent);
            Log.d(TAG, "USB permission request sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to request USB permission", e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return mConnected && mPort != null;
    }
    
    @Override
    public void disconnect() throws IOException {
        mSmoothing.stop();
        mConnected = false;
        if (mPort != null) {
            try {
                mPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing port", e);
            }
            mPort = null;
        }
    }
    
    @Override
    public void clear(int priority) throws IOException {
        // Send all black LEDs
        int ledCount = LedDataExtractor.getLedCount(mContext);
        ColorRgb[] blackLeds = new ColorRgb[ledCount];
        for(int i=0; i<ledCount; i++) blackLeds[i] = new ColorRgb(0,0,0);
        
        mSmoothing.setTargetColors(blackLeds);
    }
    
    @Override
    public void clearAll() throws IOException {
        clear(mPriority);
    }
    
    @Override
    public void setColor(int color, int priority) throws IOException {
        setColor(color, priority, -1);
    }
    
    @Override
    public void setColor(int color, int priority, int duration_ms) throws IOException {
        // Get LED count from preferences
        int ledCount = LedDataExtractor.getLedCount(mContext);
        
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        ColorRgb[] leds = new ColorRgb[ledCount];
        // Fill all LEDs with the same color
        for (int i = 0; i < ledCount; i++) {
            leds[i] = new ColorRgb(r, g, b);
        }
        
        mSmoothing.setTargetColors(leds);
    }
    
    @Override
    public void setImage(byte[] data, int width, int height, int priority) throws IOException {
        setImage(data, width, height, priority, -1);
    }
    
    @Override
    public void setImage(byte[] data, int width, int height, int priority, int duration_ms) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to Adalight device");
        }
        
        // Extract LED data reusing buffer
        mLedDataBuffer = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer);
        if (mLedDataBuffer.length == 0) return;
        
        // Pass to smoothing
        mSmoothing.setTargetColors(mLedDataBuffer);
    }
    
    // Callback from ColorSmoothing
    private void sendLedData(ColorRgb[] leds) {
        if (!isConnected()) return;
        
        try {
            byte[] packet = createPacket(mProtocol, leds);
            mPort.write(packet, 1000);
            
            // Log for debugging occasionally
            if ((System.currentTimeMillis() % 2000) < 50) {
                 Log.v(TAG, "Sent packet: " + leds.length + " LEDs");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send data", e);
            mConnected = false;
        }
    }
    
    private byte[] createPacket(ProtocolType protocol, ColorRgb[] leds) {
        switch (protocol) {
            case ADA:
                return createAdaPacket(leds);
            case LBAPA:
                return createLbapaPacket(leds);
            case AWA:
                return createAwaPacket(leds);
            default:
                return createAdaPacket(leds);
        }
    }

    private byte[] createAdaPacket(ColorRgb[] leds) {
        int ledCount = leds.length;
        int dataSize = ledCount * 3;
        byte[] packet = new byte[6 + dataSize];
        
        // Header
        packet[0] = 'A';
        packet[1] = 'd';
        packet[2] = 'a';
        
        int ledCountMinusOne = ledCount - 1;
        packet[3] = (byte) ((ledCountMinusOne >> 8) & 0xFF);
        packet[4] = (byte) (ledCountMinusOne & 0xFF);
        packet[5] = (byte) (packet[3] ^ packet[4] ^ 0x55);
        
        // RGB data
        int offset = 6;
        for (ColorRgb led : leds) {
            packet[offset++] = (byte) led.red;
            packet[offset++] = (byte) led.green;
            packet[offset++] = (byte) led.blue;
        }
        
        return packet;
    }

    private byte[] createLbapaPacket(ColorRgb[] leds) {
        int ledCount = leds.length;
        int startFrameSize = 4;
        int endFrameSize = Math.max((ledCount + 15) / 16, 4);
        int bytesPerLed = 4;
        int dataSize = ledCount * bytesPerLed;
        
        byte[] packet = new byte[6 + startFrameSize + dataSize + endFrameSize];
        
        // Header (same as ADA)
        packet[0] = 'A';
        packet[1] = 'd';
        packet[2] = 'a';
        
        int ledCountMinusOne = ledCount - 1;
        packet[3] = (byte) ((ledCountMinusOne >> 8) & 0xFF);
        packet[4] = (byte) (ledCountMinusOne & 0xFF);
        packet[5] = (byte) (packet[3] ^ packet[4] ^ 0x55);
        
        // Start Frame (4 bytes 0x00)
        int offset = 6;
        for (int i = 0; i < startFrameSize; i++) {
            packet[offset++] = 0x00;
        }
        
        // LED data: [0xFF, R, G, B] for each LED
        for (ColorRgb led : leds) {
            packet[offset++] = (byte) 0xFF;
            packet[offset++] = (byte) led.red;
            packet[offset++] = (byte) led.green;
            packet[offset++] = (byte) led.blue;
        }
        
        // End Frame
        for (int i = 0; i < endFrameSize; i++) {
            packet[offset++] = 0x00;
        }
        
        return packet;
    }
    
    private byte[] createAwaPacket(ColorRgb[] leds) {
        int ledCount = leds.length;
        int dataSize = ledCount * 3;
        // Checksum size = 3 bytes (Fletcher)
        byte[] packet = new byte[6 + dataSize + 3];
        
        packet[0] = 'A';
        packet[1] = 'w';
        packet[2] = 'a'; // 'a' = no white calibration
        
        int ledCountMinusOne = ledCount - 1;
        packet[3] = (byte) ((ledCountMinusOne >> 8) & 0xFF);
        packet[4] = (byte) (ledCountMinusOne & 0xFF);
        packet[5] = (byte) (packet[3] ^ packet[4] ^ 0x55);
        
        int offset = 6;
        for (ColorRgb led : leds) {
            packet[offset++] = (byte) led.red;
            packet[offset++] = (byte) led.green;
            packet[offset++] = (byte) led.blue;
        }
        
        // Fletcher Checksum
        int fletcher1 = 0;
        int fletcher2 = 0;
        int fletcherExt = 0;
        
        for (int i = 0; i < dataSize; i++) {
            int val = packet[6 + i] & 0xFF;
            int position = i + 1; // 1-based index
            
            fletcherExt = (fletcherExt + (val ^ position)) % 255;
            fletcher1 = (fletcher1 + val) % 255;
            fletcher2 = (fletcher2 + fletcher1) % 255;
        }
        
        packet[offset++] = (byte) fletcher1;
        packet[offset++] = (byte) fletcher2;
        packet[offset++] = (byte) fletcherExt;
        
        return packet;
    }
}
