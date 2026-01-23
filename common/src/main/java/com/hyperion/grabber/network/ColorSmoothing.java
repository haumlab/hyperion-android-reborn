package com.hyperion.grabber.common.network;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ColorSmoothing - класс для устранения стробоскопического эффекта при обновлении LED.
 */
public class ColorSmoothing {
    private static final String TAG = "ColorSmoothing";
    private static final boolean DEBUG = false;

    // Значения по умолчанию
    private static final int DEFAULT_UPDATE_FREQUENCY_HZ = 25;
    private static final int DEFAULT_SETTLING_TIME_MS = 200;
    private static final int DEFAULT_OUTPUT_DELAY = 2;
    private static final long MIN_UPDATE_INTERVAL_MS = 1; 

    // Конфигурация
    private int mUpdateFrequencyHz = DEFAULT_UPDATE_FREQUENCY_HZ;
    private int mSettlingTimeMs = DEFAULT_SETTLING_TIME_MS;
    private int mOutputDelay = DEFAULT_OUTPUT_DELAY;
    private boolean mEnabled = true;

    // Состояние
    private ColorRgb[] mPreviousValues;
    private ColorRgb[] mTargetValues;
    private long mTargetTime;

    // Очередь вывода (Output Delay)
    private final Deque<ColorRgb[]> mOutputQueue = new ArrayDeque<>();

    // Таймер
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private volatile boolean mRunning = false;

    // Отслеживание времени последнего обновления (дебаунсинг)
    private long mLastUpdateTime;

    // Интерфейс для отправки данных
    public interface LedDataSender {
        void sendLedData(ColorRgb[] colors);
    }

    private final LedDataSender mDataSender;

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || !mEnabled) return;

            updateLeds();

            if (mRunning && mHandler != null) {
                long intervalMs = 1000L / mUpdateFrequencyHz;
                mHandler.postDelayed(this, intervalMs);
            }
        }
    };

    public ColorSmoothing(LedDataSender sender) {
        mDataSender = sender;
    }

    public void setTargetColors(ColorRgb[] targetColors) {
        if (targetColors == null || targetColors.length == 0) {
            return;
        }

        // Дебаунсинг
        long now = System.currentTimeMillis();
        if (now - mLastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            return;
        }
        mLastUpdateTime = now;

        synchronized (this) {
            mTargetTime = now + mSettlingTimeMs;
            
            // Инициализация при первом вызове или изменении размера
            if (mTargetValues == null || mTargetValues.length != targetColors.length) {
                mTargetValues = new ColorRgb[targetColors.length];
                for(int i=0; i<targetColors.length; i++) mTargetValues[i] = new ColorRgb(0,0,0);
                
                mPreviousValues = new ColorRgb[targetColors.length];
                for(int i=0; i<targetColors.length; i++) mPreviousValues[i] = new ColorRgb(0,0,0);
                
                // Copy initial state
                for(int i=0; i<targetColors.length; i++) {
                    mTargetValues[i].set(targetColors[i]);
                    mPreviousValues[i].set(targetColors[i]);
                }
                
                start();
            } else {
                // GC-free update: copy values
                for(int i=0; i<targetColors.length; i++) {
                    mTargetValues[i].set(targetColors[i]);
                }
            }
        }
    }

    private void updateLeds() {
        ColorRgb[] colorsToSend;

        synchronized (this) {
            if (mTargetValues == null || mPreviousValues == null) {
                return;
            }

            colorsToSend = interpolateFrameLinear();
            if (colorsToSend == null) return;
        }

        queueColors(colorsToSend);
    }

    private ColorRgb[] interpolateFrameLinear() {
        long now = System.currentTimeMillis();
        long deltaTime = mTargetTime - now;

        if (deltaTime <= 0) {
            // Время истекло, использовать целевые значения
            // Update mPreviousValues in-place
            for(int i=0; i<mTargetValues.length; i++) mPreviousValues[i].set(mTargetValues[i]);
            
            if (mOutputDelay == 0) return mPreviousValues;
            
            // Clone only if queueing
            ColorRgb[] ret = new ColorRgb[mPreviousValues.length];
            for(int i=0; i<mPreviousValues.length; i++) ret[i] = mPreviousValues[i].clone();
            return ret;
        }

        // Линейная интерполяция
        float k = 1.0f - (float) deltaTime / mSettlingTimeMs;
        k = Math.max(0.0f, Math.min(1.0f, k));

        int length = Math.min(mPreviousValues.length, mTargetValues.length);
        for (int i = 0; i < length; i++) {
            int rDiff = mTargetValues[i].red - mPreviousValues[i].red;
            int gDiff = mTargetValues[i].green - mPreviousValues[i].green;
            int bDiff = mTargetValues[i].blue - mPreviousValues[i].blue;
            
            int r = Math.max(0, Math.min(255, mPreviousValues[i].red + Math.round(k * rDiff)));
            int g = Math.max(0, Math.min(255, mPreviousValues[i].green + Math.round(k * gDiff)));
            int b = Math.max(0, Math.min(255, mPreviousValues[i].blue + Math.round(k * bDiff)));
            
            mPreviousValues[i].set(r, g, b);
        }

        if (mOutputDelay == 0) return mPreviousValues;

        ColorRgb[] ret = new ColorRgb[mPreviousValues.length];
        for(int i=0; i<mPreviousValues.length; i++) ret[i] = mPreviousValues[i].clone();
        return ret;
    }

    private void queueColors(ColorRgb[] ledColors) {
        if (mOutputDelay == 0) {
            sendToDevice(ledColors);
        } else {
            synchronized (mOutputQueue) {
                mOutputQueue.addLast(ledColors);
                if (mOutputQueue.size() > mOutputDelay) {
                    ColorRgb[] frameToSend = mOutputQueue.removeFirst();
                    sendToDevice(frameToSend);
                }
            }
        }
    }

    private void sendToDevice(ColorRgb[] colors) {
        if (mDataSender != null) {
            mDataSender.sendLedData(colors);
        }
    }

    public void start() {
        if (mRunning) return;

        mHandlerThread = new HandlerThread("ColorSmoothing", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mRunning = true;
        long intervalMs = 1000L / mUpdateFrequencyHz;
        mHandler.postDelayed(mUpdateRunnable, intervalMs);
    }

    public void stop() {
        mRunning = false;
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
        }
        synchronized (mOutputQueue) {
            mOutputQueue.clear();
        }
        synchronized (this) {
            mPreviousValues = null;
            mTargetValues = null;
        }
    }

    public void setSettlingTime(int ms) {
        mSettlingTimeMs = Math.max(0, Math.min(1000, ms));
    }

    public void setOutputDelay(int frames) {
        mOutputDelay = Math.max(0, Math.min(10, frames));
    }

    public void setUpdateFrequency(int hz) {
        mUpdateFrequencyHz = Math.max(1, Math.min(60, hz));
    }
}
