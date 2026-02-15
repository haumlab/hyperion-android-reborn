package com.hyperion.grabber.common.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hyperion.grabber.common.network.NetworkScanner;

import java.lang.ref.WeakReference;

/**
 * Starts a network scan for a running Hyperion server
 * and posts progress and results
 */
public class HyperionScannerTask implements NetworkScanner.Listener {
    private static final String TAG = "HyperionScannerTask";
    private static final long SCAN_TIMEOUT_MS = 10000; // 10 seconds timeout

    private WeakReference<Listener> weakListener;
    private Context context;
    private NetworkScanner scanner;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean found = false;

    public HyperionScannerTask(@NonNull Context context, Listener listener){
        this.context = context;
        weakListener = new WeakReference<>(listener);
    }

    public void execute() {
        Log.d(TAG, "Starting scan");
        scanner = new NetworkScanner(context);

        // Report initial progress
        onProgressUpdate(0f);

        // Start scanner
        scanner.start(this);

        // Schedule timeout
        mainHandler.postDelayed(() -> {
            if (!found) {
                Log.d(TAG, "Scan timed out");
                stopScan();
                onPostExecute(null);
            }
        }, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.stop();
        }
    }

    @Override
    public void onServiceFound(@NonNull String ip) {
        if (found) return;
        found = true;
        Log.d(TAG, "Service found: " + ip);
        stopScan();
        onPostExecute(ip);
    }

    @Override
    public void onScanError(int errorCode) {
        if (found) return;
        Log.e(TAG, "Scan error: " + errorCode);
        // We let the timeout handle the failure notification unless we want to fail fast.
        // But mDNS errors might be transient or recoverable?
        // Usually errorCode tells us if it failed to start.
        // If it failed to start, we should probably fail immediately.
        // But NetworkScanner handles internal stop.
        // Let's rely on timeout to notify failure to UI, to be robust.
    }

    private void onProgressUpdate(Float value) {
        if(weakListener.get() != null){
            weakListener.get().onScannerProgress(value);
        }
    }

    private void onPostExecute(String result) {
        if(weakListener.get() != null){
            weakListener.get().onScannerCompleted(result);
        }
    }

    public interface Listener {
        void onScannerProgress(float progress);
        void onScannerCompleted(@Nullable String foundIpAddress);
    }
}
