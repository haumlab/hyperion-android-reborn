package com.hyperion.grabber.common.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;

/**
 * Utility class to grant permissions to the app via ADB over localhost.
 * Similar to ATVTools approach - connects to the device's own ADB server.
 * 
 * Requirements:
 * 1. ADB over network must be enabled on the device
 * 2. User must accept the ADB authorization prompt
 */
public class AdbSelfPermission {
    private static final String TAG = "AdbSelfPermission";
    
    // ADB protocol constants
    private static final int A_CNXN = 0x4e584e43; // CNXN
    private static final int A_AUTH = 0x48545541; // AUTH
    private static final int A_OPEN = 0x4e45504f; // OPEN
    private static final int A_OKAY = 0x59414b4f; // OKAY
    private static final int A_CLSE = 0x45534c43; // CLSE
    private static final int A_WRTE = 0x45545257; // WRTE
    
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    
    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    
    private static final int DEFAULT_ADB_PORT = 5555;
    private static final int CONNECTION_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context context;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private KeyPair keyPair;
    private int localId = 1;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    
    public interface PermissionCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(String status);
        void onAdbNotEnabled();
        void onAuthorizationRequired();
    }
    
    public AdbSelfPermission(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Check if ADB over network is likely enabled
     */
    public static boolean isAdbEnabled() {
        try {
            Socket testSocket = new Socket();
            testSocket.connect(new InetSocketAddress("127.0.0.1", DEFAULT_ADB_PORT), 1000);
            testSocket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Try to enable ADB over network via shell (may not work without root)
     */
    public static void tryEnableAdb() {
        new Thread(() -> {
            String[] commands = {
                "setprop service.adb.tcp.port 5555",
                "stop adbd",
                "start adbd",
                "settings put global adb_enabled 1",
            };
            for (String cmd : commands) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    p.waitFor();
                } catch (Exception ignored) {}
            }
        }).start();
    }
    
    /**
     * Grant all required permissions for the app via ADB
     */
    public void grantAllPermissions(PermissionCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        
        executor.execute(() -> {
            try {
                // Check if ADB is enabled
                if (!isAdbEnabled()) {
                    mainHandler.post(callback::onAdbNotEnabled);
                    return;
                }
                
                mainHandler.post(() -> callback.onProgress("Connecting to ADB..."));
                
                // Connect to ADB
                if (!connect()) {
                    mainHandler.post(() -> callback.onError("Failed to connect to ADB"));
                    return;
                }
                
                mainHandler.post(() -> callback.onProgress("Authenticating..."));
                
                // Authenticate
                if (!authenticate()) {
                    mainHandler.post(callback::onAuthorizationRequired);
                    disconnect();
                    return;
                }
                
                mainHandler.post(() -> callback.onProgress("Granting permissions..."));
                
                String pkg = context.getPackageName();
                String[] commands = {
                    // Critical for foreground service on TCL
                    "appops set " + pkg + " START_FOREGROUND allow",
                    "appops set " + pkg + " INSTANT_APP_START_FOREGROUND allow",
                    
                    // Media projection
                    "appops set " + pkg + " PROJECT_MEDIA allow",
                    
                    // Overlay
                    "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                    "pm grant " + pkg + " android.permission.SYSTEM_ALERT_WINDOW",
                    
                    // Background execution
                    "appops set " + pkg + " RUN_IN_BACKGROUND allow",
                    "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                    
                    // Battery optimization whitelist
                    "cmd deviceidle whitelist +" + pkg,
                    
                    // TCL specific
                    "settings put global tcl_app_boot_" + pkg + " allow",
                    "settings put global auto_start_" + pkg + " 1",
                };
                
                int successCount = 0;
                for (String cmd : commands) {
                    try {
                        String result = executeShellCommand(cmd);
                        if (result != null) {
                            successCount++;
                            Log.d(TAG, "Success: " + cmd);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Command failed: " + cmd + " - " + e.getMessage());
                    }
                }
                
                disconnect();
                
                final int success = successCount;
                mainHandler.post(() -> callback.onSuccess("Granted " + success + " permissions successfully!"));
                
            } catch (Exception e) {
                Log.e(TAG, "Permission grant failed", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
                disconnect();
            }
        });
    }
    
    /**
     * Execute a single shell command via ADB
     */
    public void executeCommand(String command, PermissionCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        
        executor.execute(() -> {
            try {
                if (!isAdbEnabled()) {
                    mainHandler.post(callback::onAdbNotEnabled);
                    return;
                }
                
                if (!connect()) {
                    mainHandler.post(() -> callback.onError("Failed to connect to ADB"));
                    return;
                }
                
                if (!authenticate()) {
                    mainHandler.post(callback::onAuthorizationRequired);
                    disconnect();
                    return;
                }
                
                String result = executeShellCommand(command);
                disconnect();
                
                if (result != null) {
                    mainHandler.post(() -> callback.onSuccess(result));
                } else {
                    mainHandler.post(() -> callback.onError("Command returned no result"));
                }
                
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
                disconnect();
            }
        });
    }
    
    private boolean connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", DEFAULT_ADB_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            
            connected.set(true);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
            return false;
        }
    }
    
    private void disconnect() {
        connected.set(false);
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
        input = null;
        output = null;
    }
    
    private boolean authenticate() throws IOException {
        // Send CNXN
        String identity = "host::hyperion-grabber";
        sendMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, identity.getBytes());
        
        // Read response
        AdbMessage response = readMessage();
        if (response == null) return false;
        
        if (response.command == A_AUTH && response.arg0 == AUTH_TOKEN) {
            // Need to sign the token or send public key
            // Generate key pair if needed
            if (keyPair == null) {
                keyPair = generateKeyPair();
            }
            
            if (keyPair != null) {
                // Try signature first
                byte[] signature = signToken(response.data);
                if (signature != null) {
                    sendMessage(A_AUTH, AUTH_SIGNATURE, 0, signature);
                    
                    response = readMessage();
                    if (response != null && response.command == A_CNXN) {
                        return true;
                    }
                }
                
                // Send public key
                byte[] publicKey = getAdbPublicKey();
                sendMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey);
                
                // Wait for user to accept on device
                response = readMessage();
                if (response != null && response.command == A_CNXN) {
                    return true;
                }
            }
            
            return false;
        } else if (response.command == A_CNXN) {
            // Already connected (no auth required)
            return true;
        }
        
        return false;
    }
    
    private String executeShellCommand(String command) throws IOException {
        int localId = this.localId++;
        String destination = "shell:" + command;
        
        sendMessage(A_OPEN, localId, 0, destination.getBytes());
        
        AdbMessage response = readMessage();
        if (response == null || response.command != A_OKAY) {
            return null;
        }
        
        int remoteId = response.arg0;
        StringBuilder result = new StringBuilder();
        
        // Read output
        while (true) {
            response = readMessage();
            if (response == null) break;
            
            if (response.command == A_WRTE) {
                if (response.data != null) {
                    result.append(new String(response.data));
                }
                // Send OKAY
                sendMessage(A_OKAY, localId, remoteId, null);
            } else if (response.command == A_CLSE) {
                break;
            }
        }
        
        // Close
        sendMessage(A_CLSE, localId, remoteId, null);
        
        return result.toString();
    }
    
    private void sendMessage(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24);
        header.order(ByteOrder.LITTLE_ENDIAN);
        
        int dataLength = (data != null) ? data.length : 0;
        int dataChecksum = (data != null) ? checksum(data) : 0;
        int magic = command ^ 0xFFFFFFFF;
        
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(dataLength);
        header.putInt(dataChecksum);
        header.putInt(magic);
        
        output.write(header.array());
        if (data != null && data.length > 0) {
            output.write(data);
        }
        output.flush();
    }
    
    private AdbMessage readMessage() throws IOException {
        byte[] header = new byte[24];
        try {
            input.readFully(header);
        } catch (IOException e) {
            return null;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(header);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        AdbMessage message = new AdbMessage();
        message.command = buffer.getInt();
        message.arg0 = buffer.getInt();
        message.arg1 = buffer.getInt();
        int dataLength = buffer.getInt();
        int dataChecksum = buffer.getInt();
        int magic = buffer.getInt();
        
        if (dataLength > 0) {
            message.data = new byte[dataLength];
            input.readFully(message.data);
        }
        
        return message;
    }
    
    private int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return sum;
    }
    
    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key pair", e);
            return null;
        }
    }
    
    private byte[] signToken(byte[] token) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
            return cipher.doFinal(token);
        } catch (Exception e) {
            Log.e(TAG, "Failed to sign token", e);
            return null;
        }
    }
    
    private byte[] getAdbPublicKey() {
        try {
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            
            // ADB uses a specific format for the public key
            byte[] encoded = publicKey.getEncoded();
            String base64Key = Base64.encodeToString(encoded, Base64.NO_WRAP);
            String adbKey = base64Key + " hyperion-grabber@android\0";
            
            return adbKey.getBytes();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get public key", e);
            return new byte[0];
        }
    }
    
    private static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
}
