package com.hyperion.grabber.common.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import androidx.preference.PreferenceManager;

import com.hyperion.grabber.common.R;
import com.hyperion.grabber.common.network.ColorRgb;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LedDataExtractorBenchmark {

    @Mock
    Context context;
    @Mock
    SharedPreferences sharedPreferences;
    @Mock
    Resources resources;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock Resources
        when(context.getResources()).thenReturn(resources);
        // Mock getting the key string from resource ID
        when(resources.getString(R.string.pref_key_x_led)).thenReturn("pref_key_x_led");
        when(resources.getString(R.string.pref_key_y_led)).thenReturn("pref_key_y_led");

        // Mock defaultKey logic
        when(resources.getResourceEntryName(anyInt())).thenReturn("pref_key_test");
        when(resources.getIdentifier(anyString(), anyString(), anyString())).thenReturn(0);

        // We need to handle PreferenceManager.getDefaultSharedPreferences(context).
        // Since we cannot easily mock the static method without extra setup (and Robolectric might interfere),
        // we will rely on Robolectric's behavior if possible, or try to mock Context.getSharedPreferences
        // assuming PreferenceManager uses it.

        // Actually, if we use Robolectric, we can use RuntimeEnvironment.application as context,
        // which has a working SharedPreferences.
        // But we want to isolate the benchmark to just the Preferences class overhead + logic,
        // not necessarily the full Android SharedPreferences impl overhead (though that is part of it).

        // Let's try to mock the static method PreferenceManager.getDefaultSharedPreferences if possible.
        // But for now, let's assume Robolectric handles it or we can just mock context.getPackageName()
        // because PreferenceManager uses it to find the default prefs file.
        when(context.getPackageName()).thenReturn("com.hyperion.grabber.common");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        // Mock SharedPreferences values
        when(sharedPreferences.getString(eq("pref_key_x_led"), any())).thenReturn("10");
        when(sharedPreferences.getString(eq("pref_key_y_led"), any())).thenReturn("10");
        when(sharedPreferences.contains(anyString())).thenReturn(true);
    }

    @Test
    public void benchmarkCurrentImplementation() {
        int width = 100;
        int height = 100;
        byte[] screenData = new byte[width * height * 3];

        // Warmup
        for (int i = 0; i < 100; i++) {
            LedDataExtractor.extractLEDData(context, screenData, width, height, null);
        }

        long startTime = System.nanoTime();
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            LedDataExtractor.extractLEDData(context, screenData, width, height, null);
        }
        long endTime = System.nanoTime();

        long duration = endTime - startTime;
        double avgTimeMs = (double) duration / iterations / 1_000_000.0;

        System.out.println("Baseline Benchmark: " + avgTimeMs + " ms per call");
    }

    @Test
    public void benchmarkOptimizedImplementation() {
        int width = 100;
        int height = 100;
        byte[] screenData = new byte[width * height * 3];
        int xLed = 10;
        int yLed = 10;

        // Warmup
        for (int i = 0; i < 100; i++) {
            LedDataExtractor.extractLEDData(screenData, width, height, xLed, yLed, null);
        }

        long startTime = System.nanoTime();
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            LedDataExtractor.extractLEDData(screenData, width, height, xLed, yLed, null);
        }
        long endTime = System.nanoTime();

        long duration = endTime - startTime;
        double avgTimeMs = (double) duration / iterations / 1_000_000.0;

        System.out.println("Optimized Benchmark: " + avgTimeMs + " ms per call");
    }
}
