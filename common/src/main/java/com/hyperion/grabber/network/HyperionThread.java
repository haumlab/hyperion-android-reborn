package com.hyperion.grabber.common.network;

import com.hyperion.grabber.common.HyperionScreenService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HyperionThread extends Thread {

    private String HOST;
    private int PORT;
    private int PRIORITY;
    private final int FRAME_DURATION = -1;
    private boolean RECONNECT = false;
    private boolean HAS_CONNECTED = false;
    private int RECONNECT_DELAY;
    private HyperionClient mHyperion;
    private final ExecutorService mSenderExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> mCurrentSendTask = null;
    private volatile FrameData mPendingFrame = null;

    private static class FrameData {
        byte[] data;
        int width;
        int height;
        
        FrameData(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    HyperionScreenService.HyperionThreadBroadcaster mSender;
    HyperionThreadListener mReceiver = new HyperionThreadListener() {
        @Override
        public void sendFrame(byte[] data, int width, int height) {
            if (mHyperion == null || !mHyperion.isConnected()) {
                return;
            }
            
            // Store the latest frame
            mPendingFrame = new FrameData(data, width, height);
            
            // Cancel any pending send task that hasn't started yet
            Future<?> currentTask = mCurrentSendTask;
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(false);
            }
            
            // Submit new task to send the frame asynchronously
            mCurrentSendTask = mSenderExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    FrameData frame = mPendingFrame;
                    if (frame != null && mHyperion != null && mHyperion.isConnected()) {
                        try {
                            mHyperion.setImage(frame.data, frame.width, frame.height, PRIORITY, FRAME_DURATION);
                            // Periodically clean up replies to prevent buffer buildup
                            if (mHyperion instanceof HyperionFlatBuffers) {
                                ((HyperionFlatBuffers) mHyperion).cleanReplies();
                            }
                        } catch (IOException e) {
                            mSender.onConnectionError(e.hashCode(), e.getMessage());
                            e.printStackTrace();
                            if (RECONNECT && HAS_CONNECTED) {
                                reconnectDelay(RECONNECT_DELAY);
                                try {
                                    mHyperion = createClient();
                                } catch (IOException i) {
                                    i.printStackTrace();
                                }
                            }
                        }
                    }
                }
            });
        }

        @Override
        public void clear() {
            if (mHyperion != null && mHyperion.isConnected()) {
                try {
                    mHyperion.clear(PRIORITY);
                } catch (IOException e) {
                    mSender.onConnectionError(e.hashCode(), e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void disconnect() {
            if (mSenderExecutor != null) {
                mSenderExecutor.shutdownNow();
            }
            if (mHyperion != null) {
                try {
                    mHyperion.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void sendStatus(boolean isGrabbing) {
            if (mSender != null) {
                mSender.onReceiveStatus(isGrabbing);
            }
        }
    };

    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster listener, final String host,
                          final int port, final int priority, final boolean reconnect, final int delay){
        HOST = host;
        PORT = port;
        PRIORITY = priority;
        RECONNECT = reconnect;
        RECONNECT_DELAY = delay * 1000;
        mSender = listener;
    }

    public HyperionThreadListener getReceiver() {return mReceiver;}

    private HyperionClient createClient() throws IOException {
        return new HyperionFlatBuffers(HOST, PORT, PRIORITY);
    }

    @Override
    public void run(){
        do {
            try {
                mHyperion = createClient();
            } catch (IOException e) {
                mSender.onConnectionError(e.hashCode(), e.getMessage());
                e.printStackTrace();
                if (RECONNECT && HAS_CONNECTED) {
                    reconnectDelay(RECONNECT_DELAY);
                }
            } finally {
                if (mHyperion != null && mSender != null && mHyperion.isConnected()) {
                    HAS_CONNECTED = true;
                    mSender.onConnected();
                    break;
                }
            }
        } while (RECONNECT && HAS_CONNECTED);
    }
    
    public void reconnectDelay(long t) {
        try {
            Thread.sleep(t);
        } catch (InterruptedException e) {
            RECONNECT = false;
            HAS_CONNECTED = false;
            Thread.currentThread().interrupt();
        }
    }

    public interface HyperionThreadListener {
        void sendFrame(byte[] data, int width, int height);
        void clear();
        void disconnect();
        void sendStatus(boolean isGrabbing);
    }
}
