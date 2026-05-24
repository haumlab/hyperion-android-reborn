package com.hyperion.grabber.common.util;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class AudioVisualizerController {
    private static final String TAG = "AudioVisualizer";
    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_SIZE = 2048;
    private static final int SPECTRUM_BANDS = 64;
    
    private AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicIntegerArray spectrumData = new AtomicIntegerArray(SPECTRUM_BANDS);
    private volatile int[] audioWaveform = new int[BUFFER_SIZE];
    
    public AudioVisualizerController() {
        initAudioRecord();
    }
    
    private void initAudioRecord() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(bufferSize, BUFFER_SIZE * 2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioRecord", e);
        }
    }
    
    public void start() {
        if (isCapturing.getAndSet(true)) return;
        if (audioRecord == null) return;
        
        try {
            audioRecord.startRecording();
            captureThread = new Thread(this::captureAudio, "AudioCapture");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio capture", e);
            isCapturing.set(false);
        }
    }
    
    public void stop() {
        if (!isCapturing.getAndSet(false)) return;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
        }
        
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void captureAudio() {
        if (audioRecord == null) return;
        
        short[] buffer = new short[BUFFER_SIZE];
        
        while (isCapturing.get()) {
            try {
                int readSize = audioRecord.read(buffer, 0, BUFFER_SIZE);
                if (readSize > 0) {
                    processAudio(buffer, readSize);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading audio", e);
            }
        }
    }
    
    private void processAudio(short[] buffer, int size) {
        int[] waveform = new int[size];
        for (int i = 0; i < size; i++) {
            waveform[i] = Math.abs(buffer[i]);
        }
        audioWaveform = waveform;
        
        computeSpectrum(buffer, size);
    }
    
    private void computeSpectrum(short[] buffer, int size) {
        int[] spectrum = new int[SPECTRUM_BANDS];
        
        for (int band = 0; band < SPECTRUM_BANDS; band++) {
            int bandWidth = size / SPECTRUM_BANDS;
            int startIdx = band * bandWidth;
            int endIdx = startIdx + bandWidth;
            
            long sum = 0;
            for (int i = startIdx; i < endIdx && i < size; i++) {
                sum += Math.abs(buffer[i]);
            }
            
            int avgMagnitude = (int) (sum / bandWidth);
            spectrum[band] = Math.min(255, avgMagnitude / 128);
        }
        
        for (int i = 0; i < SPECTRUM_BANDS; i++) {
            spectrumData.set(i, spectrum[i]);
        }
    }
    
    public int getSpectrumBand(int band) {
        if (band < 0 || band >= SPECTRUM_BANDS) return 0;
        return spectrumData.get(band);
    }
    
    public int[] getFullSpectrum() {
        int[] result = new int[SPECTRUM_BANDS];
        for (int i = 0; i < SPECTRUM_BANDS; i++) {
            result[i] = spectrumData.get(i);
        }
        return result;
    }
    
    public int[] getWaveform() {
        return audioWaveform;
    }
    
    public int getWaveformSample(int index) {
        if (index < 0 || index >= audioWaveform.length) return 0;
        return audioWaveform[index];
    }
    
    public void release() {
        stop();
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio record", e);
            }
            audioRecord = null;
        }
    }
    
    public boolean isCapturing() {
        return isCapturing.get();
    }
    
    public static int[] generateAudioVisualization(int width, int height, int[] spectrum) {
        int[] visualization = new int[width * height * 3];
        int bandWidth = Math.max(1, width / spectrum.length);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int band = Math.min(x / bandWidth, spectrum.length - 1);
                int intensity = spectrum[band];
                
                float normalizedHeight = (float) y / height;
                int value = (int) (intensity * (1.0f - normalizedHeight));
                
                int idx = (y * width + x) * 3;
                visualization[idx] = Math.min(255, value);
                visualization[idx + 1] = Math.min(255, value * 2 / 3);
                visualization[idx + 2] = Math.min(255, value / 2);
            }
        }
        
        return visualization;
    }
}
