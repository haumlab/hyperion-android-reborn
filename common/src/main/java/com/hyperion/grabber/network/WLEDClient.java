package com.hyperion.grabber.common.network;

import android.content.Context;
import android.util.Log;

import com.hyperion.grabber.common.util.LedDataExtractor;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WLEDClient implements HyperionClient {
    private static final String TAG = "WLEDClient";
    private static final int DEFAULT_PORT_DDP = 4048;
    private static final int DEFAULT_PORT_DRGB = 19446;
    
    // DDP Constants
    private static final int DDP_HEADER_SIZE = 10;
    private static final int DDP_MAX_LEDS_PER_PACKET = 480;
    private static final int DDP_CHANNELS_PER_PACKET = DDP_MAX_LEDS_PER_PACKET * 3;
    
    // UDP Raw Constants
    private static final byte PROTOCOL_DRGB = 2;
    private static final byte PROTOCOL_DNRGB = 4;
    private static final int MAX_LEDS_DRGB = 490;
    private static final int MAX_LEDS_PER_PACKET_DNRGB = 489;
    private static final byte WLED_TIMEOUT_SECONDS = 5;

    public enum Protocol {
        DDP,
        UDP_RAW // DRGB/DNRGB
    }

    private final Context mContext; // Needed for LedDataExtractor
    private final String mHost;
    private final int mPort;
    private final int mPriority;
    private final String mColorOrder;
    private final Protocol mProtocol = Protocol.DDP; // Default to DDP
    
    private volatile boolean mConnected = false;
    private DatagramSocket mSocket;
    private InetAddress mAddress;
    
    private final ColorSmoothing mSmoothing;
    private ColorRgb[] mLedDataBuffer;
    
    // KeepAlive
    private final ScheduledExecutorService mKeepAliveExecutor;
    private ColorRgb[] mLastLeds = null;
    
    public WLEDClient(Context context, String address, int port, int priority, String colorOrder) throws IOException {
        mContext = context;
        mHost = address;
        // Use default port based on protocol if not specified
        if (port <= 0 || port == 80) {
            mPort = (mProtocol == Protocol.DDP) ? DEFAULT_PORT_DDP : DEFAULT_PORT_DRGB;
        } else {
            mPort = port;
        }
        mPriority = priority;
        mColorOrder = colorOrder != null ? colorOrder.toLowerCase() : "rgb";
        
        mSmoothing = new ColorSmoothing(this::sendLedData);
        // Configure smoothing for Ambilight (Low Latency)
        mSmoothing.setSettlingTime(100); // Fast transition (100ms)
        mSmoothing.setOutputDelay(0);    // No buffering delay
        mSmoothing.setUpdateFrequency(40); // 40Hz update rate
        
        mKeepAliveExecutor = Executors.newSingleThreadScheduledExecutor();
        connect();
        startKeepAlive();
    }
    
    private void startKeepAlive() {
        mKeepAliveExecutor.scheduleWithFixedDelay(() -> {
            if (!mConnected || mLastLeds == null) return;
            // Resend last frame to keep alive
            sendLedData(mLastLeds); 
        }, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    
    private void connect() throws IOException {
        try {
            mAddress = InetAddress.getByName(mHost);
            mSocket = new DatagramSocket();
            mSocket.setSoTimeout(1000);
            mConnected = true;
            mSmoothing.start();
            Log.d(TAG, "Connected to WLED at " + mHost + ":" + mPort);
        } catch (Exception e) {
            mConnected = false;
            throw new IOException("Failed to connect to WLED: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return mConnected && mSocket != null && !mSocket.isClosed();
    }
    
    @Override
    public void disconnect() throws IOException {
        mConnected = false;
        mSmoothing.stop();
        mKeepAliveExecutor.shutdownNow();
        if (mSocket != null && !mSocket.isClosed()) {
            mSocket.close();
            mSocket = null;
        }
    }
    
    @Override
    public void clear(int priority) throws IOException {
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
        int ledCount = LedDataExtractor.getLedCount(mContext);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        ColorRgb[] leds = new ColorRgb[ledCount];
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
            throw new IOException("Not connected to WLED");
        }
        
        // Extract LED data reusing buffer
        mLedDataBuffer = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer);
        if (mLedDataBuffer.length == 0) return;
        
        mSmoothing.setTargetColors(mLedDataBuffer);
    }
    
    private void sendLedData(ColorRgb[] leds) {
        if (!isConnected()) return;
        mLastLeds = leds; // Save for keepalive
        
        try {
            if (mProtocol == Protocol.DDP) {
                List<byte[]> packets = createDdpPackets(leds);
                for (byte[] packet : packets) {
                    sendPacket(packet);
                }
            } else {
                // Fallback to UDP Raw
                sendUdpRaw(leds);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send data to WLED", e);
        }
    }
    
    private void sendPacket(byte[] packet) throws IOException {
        if (mSocket == null || mAddress == null) return;
        DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, mAddress, mPort);
        mSocket.send(datagramPacket);
    }

    // DDP Protocol Implementation
    private List<byte[]> createDdpPackets(ColorRgb[] leds) {
        List<byte[]> packets = new ArrayList<>();
        int channelCount = leds.length * 3;
        int packetCount = (channelCount + DDP_CHANNELS_PER_PACKET - 1) / DDP_CHANNELS_PER_PACKET;
        
        int channelOffset = 0;
        
        for (int packetIndex = 0; packetIndex < packetCount; packetIndex++) {
            boolean isLastPacket = (packetIndex == packetCount - 1);
            int packetDataSize = isLastPacket ? 
                (channelCount - channelOffset) : DDP_CHANNELS_PER_PACKET;
            
            byte[] packet = new byte[DDP_HEADER_SIZE + packetDataSize];
            
            // Header
            packet[0] = (byte) (0x40 | (isLastPacket ? 0x01 : 0x00)); // VER1 | PUSH
            packet[1] = (byte) ((packetIndex + 1) & 0x0F); // sequence number
            packet[2] = (byte) (0x80 | (1 << 3) | 5); // customerDefined | RGB | Pixel8
            
            packet[3] = 0x01; // ID: DISPLAY
            
            // Offset (Big Endian)
            int offset = channelOffset; // Offset in BYTES (channels)
            packet[4] = (byte) ((offset >> 24) & 0xFF);
            packet[5] = (byte) ((offset >> 16) & 0xFF);
            packet[6] = (byte) ((offset >> 8) & 0xFF);
            packet[7] = (byte) (offset & 0xFF);
            
            // Length (Big Endian)
            packet[8] = (byte) ((packetDataSize >> 8) & 0xFF);
            packet[9] = (byte) (packetDataSize & 0xFF);
            
            // Data
            int dataIdx = DDP_HEADER_SIZE;
            int ledsProcessed = channelOffset / 3;
            int ledsInThisPacket = packetDataSize / 3;
            
            for (int i = 0; i < ledsInThisPacket; i++) {
                ColorRgb led = leds[ledsProcessed + i];
                packet[dataIdx++] = (byte) led.red;
                packet[dataIdx++] = (byte) led.green;
                packet[dataIdx++] = (byte) led.blue;
            }
            
            packets.add(packet);
            channelOffset += packetDataSize;
        }
        
        return packets;
    }
    
    // Legacy UDP Raw (DRGB/DNRGB)
    private void sendUdpRaw(ColorRgb[] leds) throws IOException {
        int ledCount = leds.length;
        byte[] packet;
        
        if (ledCount <= MAX_LEDS_DRGB) {
             packet = createDRGBPacket(leds);
             sendPacket(packet);
        } else {
             // Split
             int startIndex = 0;
             int remaining = ledCount;
             while (remaining > 0) {
                 int ledsInPacket = Math.min(remaining, MAX_LEDS_PER_PACKET_DNRGB);
                 packet = createDNRGBPacket(leds, startIndex, ledsInPacket);
                 sendPacket(packet);
                 startIndex += ledsInPacket;
                 remaining -= ledsInPacket;
             }
        }
    }
    
    private byte[] createDRGBPacket(ColorRgb[] leds) {
        byte[] packet = new byte[2 + leds.length * 3];
        packet[0] = PROTOCOL_DRGB;
        packet[1] = WLED_TIMEOUT_SECONDS;
        
        int idx = 2;
        for (ColorRgb led : leds) {
            convertColorOrder(led, mColorOrder, packet, idx);
            idx += 3;
        }
        return packet;
    }
    
    private byte[] createDNRGBPacket(ColorRgb[] leds, int startIndex, int count) {
        byte[] packet = new byte[4 + count * 3];
        packet[0] = PROTOCOL_DNRGB;
        packet[1] = WLED_TIMEOUT_SECONDS;
        packet[2] = (byte) ((startIndex >> 8) & 0xFF);
        packet[3] = (byte) (startIndex & 0xFF);
        
        int idx = 4;
        for (int i = 0; i < count; i++) {
            ColorRgb led = leds[startIndex + i];
            convertColorOrder(led, mColorOrder, packet, idx);
            idx += 3;
        }
        return packet;
    }
    
    private void convertColorOrder(ColorRgb led, String order, byte[] buffer, int offset) {
        byte r = (byte) led.red;
        byte g = (byte) led.green;
        byte b = (byte) led.blue;
        
        switch (order) {
            case "grb": buffer[offset] = g; buffer[offset+1] = r; buffer[offset+2] = b; break;
            case "brg": buffer[offset] = b; buffer[offset+1] = r; buffer[offset+2] = g; break;
            case "rbg": buffer[offset] = r; buffer[offset+1] = b; buffer[offset+2] = g; break;
            case "gbr": buffer[offset] = g; buffer[offset+1] = b; buffer[offset+2] = r; break;
            case "bgr": buffer[offset] = b; buffer[offset+1] = g; buffer[offset+2] = r; break;
            default:    buffer[offset] = r; buffer[offset+1] = g; buffer[offset+2] = b; break; // rgb
        }
    }
}
