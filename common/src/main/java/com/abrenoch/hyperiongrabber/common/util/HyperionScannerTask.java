package com.abrenoch.hyperiongrabber.common.util;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.util.Log;

import com.abrenoch.hyperiongrabber.common.network.NetworkScanner;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Starts a network scan for a running Hyperion server
 * and posts progress and results
 */
public class HyperionScannerTask {
    private WeakReference<Listener> weakListener;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public HyperionScannerTask(Listener listener){
        weakListener = new WeakReference<>(listener);
    }

    public void execute() {
        executor.execute(() -> {
            String result = doInBackground();
            mainHandler.post(() -> onPostExecute(result));
        });
    }

    private String doInBackground() {
        Log.d("Hyperion scanner", "starting scan");
        NetworkScanner networkScanner = new NetworkScanner();

        String result;
        while (networkScanner.hasNextAttempt()){
            result = networkScanner.tryNext();

            if (result != null){
                return result;
            }

            final float progress = networkScanner.getProgress();
            mainHandler.post(() -> onProgressUpdate(progress));
        }

        return null;
    }

    private void onProgressUpdate(Float value) {
        Log.d("Hyperion scanner", "scan progress: " + value);
        if(weakListener.get() != null){
            weakListener.get().onScannerProgress(value);
        }
    }

    private void onPostExecute(String result) {
        Log.d("Hyperion scanner", "scan result: " + result);
        if(weakListener.get() != null){
            weakListener.get().onScannerCompleted(result);
        }
    }

    public interface Listener {

        void onScannerProgress(float progress);
        void onScannerCompleted(@Nullable String foundIpAddress);

    }
}