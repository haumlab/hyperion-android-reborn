package com.hyperion.grabber.common.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.net.InetAddress;

/**
 * Scans the local network for running Hyperion servers using mDNS.
 * Created by nino on 27-5-18.
 */

public class NetworkScanner {
    public static final int PORT = 19400;
    // Note: The service type must match what Hyperion advertises.
    // Commonly _hyperiond-flatbuf._tcp.
    private static final String SERVICE_TYPE = "_hyperiond-flatbuf._tcp.";
    private static final String TAG = "NetworkScanner";

    private final NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private Listener listener;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onServiceFound(@NonNull String ip);
        void onScanError(int errorCode);
    }

    public NetworkScanner(@NonNull Context context) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void start(@NonNull Listener listener) {
        this.listener = listener;
        if (isScanning) {
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo);
                // Check if the service type matches (handling potential trailing dot differences)
                if (serviceInfo.getServiceType().contains("_hyperiond-flatbuf._tcp")) {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e(TAG, "Resolve failed: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            Log.d(TAG, "Service resolved: " + serviceInfo);
                            InetAddress host = serviceInfo.getHost();
                            if (host != null) {
                                String ip = host.getHostAddress();
                                notifyServiceFound(ip);
                            }
                        }
                    });
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.e(TAG, "service lost: " + serviceInfo);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
                isScanning = false;
                notifyError(errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
                isScanning = false;
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            isScanning = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start discovery", e);
            notifyError(0);
        }
    }

    public void stop() {
        if (isScanning && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery", e);
            }
            isScanning = false;
        }
    }

    private void notifyServiceFound(String ip) {
        if (listener != null) {
            mainHandler.post(() -> listener.onServiceFound(ip));
        }
    }

    private void notifyError(int errorCode) {
        if (listener != null) {
            mainHandler.post(() -> listener.onScanError(errorCode));
        }
    }
}
