package com.hyperion.grabber.common.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class AnimationSyncController {
    private static final int ANIMATION_PATTERN_LENGTH = 64;
    private static final int ANIMATION_CYCLE_MS = 1000;
    
    private final AtomicLong startTimeNs = new AtomicLong(System.nanoTime());
    private final AtomicInteger frameDelay = new AtomicInteger(0);
    private final AtomicInteger animationIntensity = new AtomicInteger(0);
    private boolean animationEnabled = false;
    
    public AnimationSyncController(int frameDelayMs, boolean enableAnimation) {
        this.frameDelay.set(frameDelayMs);
        this.animationEnabled = enableAnimation;
        this.startTimeNs.set(System.nanoTime());
    }
    
    public void setFrameDelay(int delayMs) {
        frameDelay.set(Math.max(-100, Math.min(100, delayMs)));
    }
    
    public void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
        if (enabled) {
            startTimeNs.set(System.nanoTime());
        }
    }
    
    public int getAnimationIntensity() {
        if (!animationEnabled) return 0;
        
        long elapsedMs = (System.nanoTime() - startTimeNs.get()) / 1_000_000L;
        int cyclePosition = (int)(elapsedMs % ANIMATION_CYCLE_MS);
        
        int intensity;
        if (cyclePosition < ANIMATION_CYCLE_MS / 2) {
            intensity = (cyclePosition * 255) / (ANIMATION_CYCLE_MS / 2);
        } else {
            intensity = ((ANIMATION_CYCLE_MS - cyclePosition) * 255) / (ANIMATION_CYCLE_MS / 2);
        }
        
        return intensity & 0xFF;
    }
    
    public long getEffectiveFrameDelayNs() {
        return frameDelay.get() * 1_000_000L;
    }
    
    public byte[] applyAnimationToFrame(byte[] frameData) {
        if (!animationEnabled || frameData == null || frameData.length == 0) {
            return frameData;
        }
        
        int intensity = getAnimationIntensity();
        byte[] animatedFrame = frameData.clone();
        
        int step = Math.max(1, animatedFrame.length / ANIMATION_PATTERN_LENGTH);
        for (int i = 0; i < animatedFrame.length; i += step) {
            int channel = i % 3;
            if (channel < 2) {
                int value = (animatedFrame[i] & 0xFF);
                int modulated = (value * intensity) / 255;
                animatedFrame[i] = (byte) modulated;
            }
        }
        
        return animatedFrame;
    }
    
    public void sleep(long frameIntervalMs) throws InterruptedException {
        long sleepTimeMs = Math.max(1, frameIntervalMs + frameDelay.get());
        Thread.sleep(sleepTimeMs);
    }
}
