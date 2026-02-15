package com.hyperion.grabber.common.network;

import android.content.Context;
import android.util.Log;

import com.hyperion.grabber.common.HyperionScreenService;
import com.hyperion.grabber.common.R;
import com.hyperion.grabber.common.util.Preferences;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HyperionThread extends Thread {
    private static final String TAG = "HyperionThread";
    private static final int FRAME_DURATION = -1;
    private static final int SHUTDOWN_TIMEOUT_MS = 100;

    private final String mHost;
    private final int mPort;
    private final int mPriority;
    private final int mReconnectDelayMs;
    private final String mConnectionType;
    private final Context mContext;
    private final int mBaudRate;
    private final String mWledColorOrder;
    private final String mWledProtocol;
    private final boolean mWledRgbw;
    private final int mWledBrightness;
    private final String mAdalightProtocol;
    private final HyperionScreenService.HyperionThreadBroadcaster mCallback;
    private final AtomicBoolean mReconnectEnabled;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicReference<HyperionClient> mClient = new AtomicReference<>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> mPendingTask;
    private volatile FrameData mPendingFrame;

    private static final class FrameData {
        final byte[] data;
        final int width;
        final int height;

        FrameData(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private final HyperionThreadListener mListener = new HyperionThreadListener() {
        @Override
        public void sendFrame(byte[] data, int width, int height) {
            final HyperionClient client = mClient.get();
            if (client == null || !client.isConnected()) return;

            // Если используется WLED или Adalight, сглаживание уже встроено внутри клиентов
            if (client instanceof WLEDClient || client instanceof AdalightClient) {
                 mPendingFrame = new FrameData(data, width, height);
                 final Future<?> pending = mPendingTask;
                 if (pending != null && !pending.isDone()) {
                     pending.cancel(false);
                 }
                 mPendingTask = mExecutor.submit(this::sendPendingFrame);
                 return;
            }

            // Для обычного Hyperion можно использовать локальное сглаживание, если нужно
            // Но пока оставим прямую отправку для Hyperion протокола, так как сглаживание
            // обычно делается на стороне сервера Hyperion.
            
            mPendingFrame = new FrameData(data, width, height);
            final Future<?> pending = mPendingTask;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            mPendingTask = mExecutor.submit(this::sendPendingFrame);
        }

        private void sendPendingFrame() {
            final FrameData frame = mPendingFrame;
            final HyperionClient client = mClient.get();
            
            if (frame == null || client == null || !client.isConnected()) return;

            try {
                client.setImage(frame.data, frame.width, frame.height, mPriority, FRAME_DURATION);
                
                if (client instanceof HyperionFlatBuffers) {
                    ((HyperionFlatBuffers) client).cleanReplies();
                }
            } catch (IOException e) {
                handleError(e);
            }
        }

        @Override
        public void clear() {
            final HyperionClient client = mClient.get();
            if (client != null && client.isConnected()) {
                try {
                    client.clear(mPriority);
                } catch (IOException e) {
                    mCallback.onConnectionError(e.hashCode(), e.getMessage());
                }
            }
        }

        @Override
        public void disconnect() {
            final Future<?> pending = mPendingTask;
            if (pending != null) {
                pending.cancel(true);
                mPendingTask = null;
            }
            mPendingFrame = null;

            if (!mExecutor.isShutdown()) {
                mExecutor.shutdownNow();
                try {
                    mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            final HyperionClient client = mClient.getAndSet(null);
            if (client != null) {
                try {
                    client.disconnect();
                } catch (IOException ignored) {
                }
            }
            
            mConnected.set(false);
        }

        @Override
        public void sendStatus(boolean isGrabbing) {
            mCallback.onReceiveStatus(isGrabbing);
        }

        @Override
        public boolean isBusy() {
            final Future<?> pending = mPendingTask;
            return pending != null && !pending.isDone();
        }
    };

    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster callback,
                          String host, int port, int priority,
                          boolean reconnect, int delaySeconds,
                          String connectionType, Context context, int baudRate, String wledColorOrder) {
        this(callback, host, port, priority, reconnect, delaySeconds, connectionType, context,
             baudRate, wledColorOrder, "ddp", false, 255, "ada");
    }

    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster callback,
                          String host, int port, int priority,
                          boolean reconnect, int delaySeconds,
                          String connectionType, Context context, int baudRate, String wledColorOrder,
                          String wledProtocol, boolean wledRgbw, int wledBrightness, String adalightProtocol) {
        super(TAG);
        mHost = host;
        mPort = port;
        mPriority = priority;
        mReconnectEnabled = new AtomicBoolean(reconnect);
        mReconnectDelayMs = delaySeconds * 1000;
        mConnectionType = connectionType != null ? connectionType : "hyperion";
        mContext = context;
        mBaudRate = baudRate;
        mWledColorOrder = wledColorOrder != null ? wledColorOrder : "rgb";
        mWledProtocol = wledProtocol != null ? wledProtocol : "ddp";
        mWledRgbw = wledRgbw;
        mWledBrightness = wledBrightness;
        mAdalightProtocol = adalightProtocol != null ? adalightProtocol : "ada";
        mCallback = callback;
    }

    public static HyperionThread fromPreferences(HyperionScreenService.HyperionThreadBroadcaster callback,
                                                  Context context) {
        Preferences prefs = new Preferences(context);
        
        String host = prefs.getString(R.string.pref_key_host, "");
        int port = prefs.getInt(R.string.pref_key_port, 19400);
        int priority = prefs.getInt(R.string.pref_key_priority, 100);
        boolean reconnect = prefs.getBoolean(R.string.pref_key_reconnect, true);
        int reconnectDelay = prefs.getInt(R.string.pref_key_reconnect_delay, 5);
        String connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion");
        int baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate, 115200);
        String wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb");
        
        String wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp");
        boolean wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false);
        int wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255);
        
        String adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada");
        
        return new HyperionThread(callback, host, port, priority, reconnect, reconnectDelay,
                connectionType, context, baudRate, wledColorOrder,
                wledProtocol, wledRgbw, wledBrightness, adalightProtocol);
    }

    public HyperionThreadListener getReceiver() {
        return mListener;
    }

    @Override
    public void run() {
        connect();
    }

    private void connect() {
        do {
            try {
                HyperionClient client = createClient();
                
                if (client != null && client.isConnected()) {
                    mClient.set(client);
                    mConnected.set(true);
                    mCallback.onConnected();
                    Log.i(TAG, "Connected to " + mConnectionType + " at " + mHost + ":" + mPort);
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                mCallback.onConnectionError(e.hashCode(), e.getMessage());
                if (mReconnectEnabled.get() && mConnected.get()) {
                    sleepSafe(mReconnectDelayMs);
                }
            }
        } while (mReconnectEnabled.get() && mConnected.get());
    }

    private HyperionClient createClient() throws IOException {
        if ("wled".equalsIgnoreCase(mConnectionType)) {
            // WLEDClient (context, host, port, priority, colorOrder)
            return new WLEDClient(mContext, mHost, mPort, mPriority, mWledColorOrder);
        } else if ("adalight".equalsIgnoreCase(mConnectionType)) {
            if (mContext == null) {
                throw new IOException("Context is required for Adalight connection");
            }
            // AdalightClient (context, priority, baudrate)
            return new AdalightClient(mContext, mPriority, mBaudRate);
        } else {
            // Default to Hyperion
            return new HyperionFlatBuffers(mHost, mPort, mPriority);
        }
    }

    private void handleError(IOException e) {
        mCallback.onConnectionError(e.hashCode(), e.getMessage());
        
        if (mReconnectEnabled.get() && mConnected.get()) {
            sleepSafe(mReconnectDelayMs);
            try {
                HyperionClient newClient = createClient();
                
                if (newClient != null && newClient.isConnected()) {
                    mClient.set(newClient);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            mReconnectEnabled.set(false);
            mConnected.set(false);
            Thread.currentThread().interrupt();
        }
    }

    // Методы преобразования удалены за ненадобностью в этом варианте

    public interface HyperionThreadListener {
        void sendFrame(byte[] data, int width, int height);
        void clear();
        void disconnect();
        void sendStatus(boolean isGrabbing);
        boolean isBusy();
    }
}
