package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.common.util.TclBypass;

import java.util.Objects;

public class HyperionScreenService extends Service {
    public static final String BROADCAST_ERROR = "SERVICE_ERROR";
    public static final String BROADCAST_TAG = "SERVICE_STATUS";
    public static final String BROADCAST_FILTER = "SERVICE_FILTER";
    public static final String BROADCAST_TCL_BLOCKED = "TCL_BLOCKED";
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionScreenService";
    
    private boolean mForegroundFailed = false;
    private boolean mTclBlocked = false;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;

    private static final String BASE = "com.hyperion.grabber.service.";
    public static final String ACTION_START = BASE + "ACTION_START";
    public static final String ACTION_STOP = BASE + "ACTION_STOP";
    public static final String ACTION_EXIT = BASE + "ACTION_EXIT";
    public static final String GET_STATUS = BASE + "ACTION_STATUS";
    public static final String EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_EXIT_INTENT_ID = 2;

    private boolean mReconnectEnabled = false;
    private boolean mHasConnected = false;
    private MediaProjectionManager mMediaProjectionManager;
    private HyperionThread mHyperionThread;
    private static MediaProjection sMediaProjection;
    private int mFrameRate;
    private int mCaptureQuality;
    private int mHorizontalLEDCount;
    private int mVerticalLEDCount;
    private boolean mSendAverageColor;
    private HyperionScreenEncoder mHyperionEncoder;
    private NotificationManager mNotificationManager;
    private String mStartError = null;
    private String mConnectionType = "hyperion";

    private final HyperionThreadBroadcaster mReceiver = new HyperionThreadBroadcaster() {
        @Override
        public void onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to Hyperion server");
            mHasConnected = true;
            notifyActivity();
        }

        @Override
        public void onConnectionError(int errorID, String error) {
            Log.e(TAG, "Connection error: " + (error != null ? error : "unknown"));
            if (!mHasConnected) {
                // Use appropriate error message based on connection type
                if ("adalight".equalsIgnoreCase(mConnectionType)) {
                    mStartError = getResources().getString(R.string.error_adalight_unreachable);
                } else {
                    mStartError = getResources().getString(R.string.error_server_unreachable);
                }
                haltStartup();
            } else if (mReconnectEnabled) {
                Log.i(TAG, "Attempting automatic reconnect...");
            } else {
                // Use appropriate error message based on connection type
                if ("adalight".equalsIgnoreCase(mConnectionType)) {
                    mStartError = getResources().getString(R.string.error_adalight_connection_lost);
                } else {
                    mStartError = getResources().getString(R.string.error_connection_lost);
                }
                stopSelf();
            }
        }

        @Override
        public void onReceiveStatus(boolean isCapturing) {
            if (DEBUG) Log.v(TAG, "Received status: capturing=" + isCapturing);
            notifyActivity();
        }
    };

    BroadcastReceiver mEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case Intent.ACTION_SCREEN_ON:
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_ON intent received");
                    if (mHyperionEncoder != null && !isCapturing()) {
                        if (DEBUG) Log.v(TAG, "Encoder not grabbing, attempting to restart");
                        mHyperionEncoder.resumeRecording();
                    }
                    notifyActivity();
                break;
                case Intent.ACTION_SCREEN_OFF:
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_OFF intent received");
                    if (mHyperionEncoder != null) {
                        if (DEBUG) Log.v(TAG, "Clearing current light data");
                        mHyperionEncoder.clearLights();
                    }
                break;
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received");
                    if (mHyperionEncoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        if (DEBUG) Log.v(TAG, "Configuration changed, checking orientation");
                        mHyperionEncoder.setOrientation(getResources().getConfiguration().orientation);
                    }
                break;
                case Intent.ACTION_SHUTDOWN:
                case Intent.ACTION_REBOOT:
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received");
                    stopScreenRecord();
                break;
            }
        }
    };

    @Override
    public void onCreate() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        
        // Try shell bypass on startup for TCL devices
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.i(TAG, "Detected restricted manufacturer, attempting shell bypass");
            TclBypass.tryShellBypass(this);
        }
        
        super.onCreate();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private boolean prepared() {
        Preferences prefs = new Preferences(getBaseContext());
        mConnectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion");
        String host = prefs.getString(R.string.pref_key_host, null);
        int port = prefs.getInt(R.string.pref_key_port, -1);
        String priority = prefs.getString(R.string.pref_key_priority, "100");
        mFrameRate = prefs.getInt(R.string.pref_key_framerate);
        
        try {
            mCaptureQuality = Integer.parseInt(prefs.getString(R.string.pref_key_capture_quality, "128"));
        } catch (NumberFormatException e) {
            mCaptureQuality = 128;
        }
        
        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led);
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led);
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color);
        mReconnectEnabled = prefs.getBoolean(R.string.pref_key_reconnect);
        int delay = prefs.getInt(R.string.pref_key_reconnect_delay);
        int baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate);
        String wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb");
        
        // Новые настройки WLED
        String wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp");
        boolean wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false);
        int wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255);
        
        // Новые настройки Adalight
        String adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada");
        
        // Настройки сглаживания (ColorSmoothing)
        boolean smoothingEnabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, true);
        String smoothingPreset = prefs.getString(R.string.pref_key_smoothing_preset, "balanced");
        int settlingTime = prefs.getInt(R.string.pref_key_settling_time, 200);
        int outputDelay = prefs.getInt(R.string.pref_key_output_delay, 2);
        int updateFrequency = prefs.getInt(R.string.pref_key_update_frequency, 25);

        // For Adalight, host and port are not required
        if (!"adalight".equalsIgnoreCase(mConnectionType)) {
            if (host == null || Objects.equals(host, "0.0.0.0") || Objects.equals(host, "")) {
                mStartError = getResources().getString(R.string.error_empty_host);
                return false;
            }
            if (port == -1) {
                mStartError = getResources().getString(R.string.error_empty_port);
                return false;
            }
        }
        
        if (mHorizontalLEDCount <= 0 || mVerticalLEDCount <= 0) {
            mStartError = getResources().getString(R.string.error_invalid_led_counts);
            return false;
        }
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        int priorityValue = Integer.parseInt(priority);
        
        // Use default values for host/port if not set (for Adalight)
        String finalHost = host != null ? host : "localhost";
        int finalPort = port > 0 ? port : 19400;
        
        // Создать HyperionThread с полными настройками
        mHyperionThread = new HyperionThread(mReceiver, finalHost, finalPort, priorityValue, 
                mReconnectEnabled, delay, mConnectionType, getBaseContext(), baudRate, wledColorOrder,
                wledProtocol, wledRgbw, wledBrightness, adalightProtocol,
                smoothingEnabled, smoothingPreset, settlingTime, outputDelay, updateFrequency);
        mHyperionThread.start();
        mStartError = null;
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.v(TAG, "Start command received");
        super.onStartCommand(intent, flags, startId);
        if (intent == null || intent.getAction() == null) {
            String nullItem = (intent == null ? "intent" : "action");
            if (DEBUG) Log.v(TAG, "Null " + nullItem + " provided to start command");
        } else  {
            final String action = intent.getAction();
            if (DEBUG) Log.v(TAG, "Start command action: " + String.valueOf(action));
            switch (action) {
                case ACTION_START:
                    if (mHyperionThread == null) {
                        boolean isPrepared = prepared();
                        if (isPrepared) {
                            boolean foregroundStarted = tryStartForeground();
                            
                            if (!foregroundStarted && mTclBlocked) {
                                acquireWakeLock();
                            }
                            
                            try {
                                startScreenRecord(intent);
                            } catch (SecurityException e) {
                                Log.e(TAG, "Failed to start screen recording: " + e.getMessage());
                                mStartError = getResources().getString(R.string.error_media_projection_denied);
                                haltStartup();
                                break;
                            }

                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                            intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                            intentFilter.addAction(Intent.ACTION_REBOOT);
                            intentFilter.addAction(Intent.ACTION_SHUTDOWN);

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                registerReceiver(mEventReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
                            } else {
                                registerReceiver(mEventReceiver, intentFilter);
                            }
                        } else {
                            haltStartup();
                        }
                    }
                    break;
                case ACTION_STOP:
                    stopScreenRecord();
                    break;
                case GET_STATUS:
                    notifyActivity();
                    break;
                case ACTION_EXIT:
                    stopSelf();
                    break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.v(TAG, "Ending service");

        try {
            unregisterReceiver(mEventReceiver);
        } catch (Exception e) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered");
        }

        releaseWakeLock();
        stopScreenRecord();
        stopForeground(true);
        notifyActivity();

        super.onDestroy();
    }
    
    private boolean tryStartForeground() {
        mForegroundFailed = false;
        mTclBlocked = false;
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Foreground start failed: " + e.getMessage());
            mForegroundFailed = true;
            
            String msg = e.getMessage();
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true;
            }
        }
        
        if (mForegroundFailed) {
            try {
                Thread.sleep(100);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, getNotification());
                }
                mForegroundFailed = false;
                mTclBlocked = false;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Foreground retry failed: " + e.getMessage());
                mTclBlocked = true;
            }
        }
        
        notifyTclBlocked();
        return false;
    }
    
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                            "HyperionGrabber::ScreenCapture");
                    mWakeLock.acquire(60 * 60 * 1000L);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to acquire wake lock", e);
            }
        }
    }
    
    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
                Log.i(TAG, "Wake lock released");
            } catch (Exception e) {
                Log.e(TAG, "Failed to release wake lock", e);
            }
            mWakeLock = null;
        }
    }
    
    private void notifyTclBlocked() {
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, false);
        intent.putExtra(BROADCAST_TCL_BLOCKED, true);
        intent.putExtra(BROADCAST_ERROR, "Foreground service blocked by device manufacturer");
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void haltStartup() {
        // Try to start foreground to show error, but don't fail if blocked
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start foreground during halt: " + e.getMessage());
        }
        
        notifyActivity();
        
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            mHyperionThread = null;
        }
        
        stopSelf();
    }

    private Intent buildExitButton() {
        Intent notificationIntent = new Intent(this, this.getClass());
        notificationIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        notificationIntent.setAction(ACTION_EXIT);
        return notificationIntent;
    }

    public Notification getNotification() {
        HyperionNotification notification = new HyperionNotification(this, mNotificationManager);
        String label = getString(R.string.notification_exit_button);
        notification.setAction(NOTIFICATION_EXIT_INTENT_ID, label, buildExitButton());
        return notification.buildNotification();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScreenRecord(final Intent intent) {
        if (DEBUG) Log.v(TAG, "Starting screen recorder");
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final MediaProjection projection = mMediaProjectionManager.getMediaProjection(resultCode, intent);
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        
        if (projection != null && window != null) {
            sMediaProjection = projection;
            final DisplayMetrics metrics = new DisplayMetrics();
            window.getDefaultDisplay().getRealMetrics(metrics);
            
            HyperionGrabberOptions options = new HyperionGrabberOptions(
                    mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality);
            
            if (DEBUG) Log.v(TAG, "Creating encoder: " + metrics.widthPixels + "x" + metrics.heightPixels);
            mHyperionEncoder = new HyperionScreenEncoder(
                    mHyperionThread.getReceiver(),
                    projection, 
                    metrics.widthPixels, 
                    metrics.heightPixels,
                    metrics.densityDpi, 
                    options);
            mHyperionEncoder.sendStatus();
        }
    }

    private void stopScreenRecord() {
        if (DEBUG) Log.v(TAG, "Stopping screen recorder");
        mReconnectEnabled = false;
        mNotificationManager.cancel(NOTIFICATION_ID);
        
        if (mHyperionEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping encoder");
            mHyperionEncoder.stopRecording();
            mHyperionEncoder = null;
        }
        
        releaseResource();
        
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            mHyperionThread = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void releaseResource() {
        if (sMediaProjection != null) {
            sMediaProjection.stop();
            sMediaProjection = null;
        }
    }

    boolean isCapturing() {
        return mHyperionEncoder != null && mHyperionEncoder.isCapturing();
    }

    boolean isCommunicating() {
        return isCapturing() && mHasConnected;
    }

    private void notifyActivity() {
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, isCommunicating());
        intent.putExtra(BROADCAST_ERROR, mStartError);
        if (DEBUG) {
            Log.v(TAG, "Broadcasting status: communicating=" + isCommunicating() + 
                    (mStartError != null ? ", error=" + mStartError : ""));
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public interface HyperionThreadBroadcaster {
//        void onResponse(String response);
        void onConnected();
        void onConnectionError(int errorHash, String errorString);
        void onReceiveStatus(boolean isCapturing);
    }
}
