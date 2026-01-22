package com.hyperion.grabber.common.network;

import com.hyperion.grabber.common.HyperionScreenService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages asynchronous communication with a Hyperion server.
 * Handles connection, frame sending, and automatic reconnection.
 */
public final class HyperionThread extends Thread {
    private static final String TAG = "HyperionThread";
    private static final int FRAME_DURATION = -1;
    private static final int SHUTDOWN_TIMEOUT_MS = 100;

    // Connection configuration (immutable)
    private final String mHost;
    private final int mPort;
    private final int mPriority;
    private final int mReconnectDelayMs;
    private final HyperionScreenService.HyperionThreadBroadcaster mCallback;
    
    // State
    private final AtomicBoolean mReconnectEnabled;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicReference<HyperionClient> mClient = new AtomicReference<>();
    
    // Async frame sending
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> mPendingTask;
    private volatile FrameData mPendingFrame;

    /**
     * Frame data container.
     */
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

    /**
     * Listener for encoder to send frames.
     */
    private final HyperionThreadListener mListener = new HyperionThreadListener() {
        @Override
        public void sendFrame(byte[] data, int width, int height) {
            final HyperionClient client = mClient.get();
            if (client == null || !client.isConnected()) return;

            // Store latest frame, dropping any older pending frame
            mPendingFrame = new FrameData(data, width, height);

            // Cancel pending task if not yet started
            final Future<?> pending = mPendingTask;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }

            // Submit async send
            mPendingTask = mExecutor.submit(this::sendPendingFrame);
        }

        private void sendPendingFrame() {
            final FrameData frame = mPendingFrame;
            final HyperionClient client = mClient.get();
            
            if (frame == null || client == null || !client.isConnected()) return;

            try {
                client.setImage(frame.data, frame.width, frame.height, mPriority, FRAME_DURATION);
                
                // Clean up reply buffer periodically
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
            // Cancel pending work
            final Future<?> pending = mPendingTask;
            if (pending != null) {
                pending.cancel(true);
                mPendingTask = null;
            }
            mPendingFrame = null;

            // Shutdown executor
            if (!mExecutor.isShutdown()) {
                mExecutor.shutdownNow();
                try {
                    mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Disconnect client
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
    };

    /**
     * Creates a new Hyperion connection thread.
     */
    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster callback,
                          String host, int port, int priority,
                          boolean reconnect, int delaySeconds) {
        super(TAG);
        mHost = host;
        mPort = port;
        mPriority = priority;
        mReconnectEnabled = new AtomicBoolean(reconnect);
        mReconnectDelayMs = delaySeconds * 1000;
        mCallback = callback;
    }

    public HyperionThreadListener getReceiver() {
        return mListener;
    }

    @Override
    public void run() {
        connect();
    }

    /**
     * Attempts to connect to the Hyperion server.
     */
    private void connect() {
        do {
            try {
                final HyperionClient client = new HyperionFlatBuffers(mHost, mPort, mPriority);
                if (client.isConnected()) {
                    mClient.set(client);
                    mConnected.set(true);
                    mCallback.onConnected();
                    return;
                }
            } catch (IOException e) {
                mCallback.onConnectionError(e.hashCode(), e.getMessage());
                if (mReconnectEnabled.get() && mConnected.get()) {
                    sleepSafe(mReconnectDelayMs);
                }
            }
        } while (mReconnectEnabled.get() && mConnected.get());
    }

    /**
     * Handles connection errors with optional reconnection.
     */
    private void handleError(IOException e) {
        mCallback.onConnectionError(e.hashCode(), e.getMessage());
        
        if (mReconnectEnabled.get() && mConnected.get()) {
            sleepSafe(mReconnectDelayMs);
            try {
                final HyperionClient newClient = new HyperionFlatBuffers(mHost, mPort, mPriority);
                if (newClient.isConnected()) {
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

    /**
     * Interface for frame communication.
     */
    public interface HyperionThreadListener {
        void sendFrame(byte[] data, int width, int height);
        void clear();
        void disconnect();
        void sendStatus(boolean isGrabbing);
    }
}
