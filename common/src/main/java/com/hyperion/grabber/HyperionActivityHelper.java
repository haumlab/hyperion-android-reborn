package com.hyperion.grabber.common;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.common.util.ToastThrottler;

import java.util.Locale;

public class HyperionActivityHelper {

    public static final int REQUEST_MEDIA_PROJECTION = 1;

    public static boolean isServiceRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (HyperionScreenService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void startScreenRecorder(Context context, int resultCode, Intent data) {
        BootActivity.startScreenRecorder(context, resultCode, data);
    }

    public static void stopScreenRecorder(Context context, boolean isRecorderRunning) {
        if (isRecorderRunning) {
            Intent intent = new Intent(context, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.ACTION_EXIT);
            context.startService(intent);
        }
    }

    public static void fadeView(View view, boolean visible) {
        float alpha = visible ? 1f : 0f;
        int endVisibility = visible ? View.VISIBLE : View.INVISIBLE;
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(alpha)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(endVisibility);
                    }
                })
                .start();
    }

    public static Context updateBaseContextLocale(Context context) {
        Preferences prefs = new Preferences(context);
        String languageString = prefs.getLocale();
        Locale locale = new Locale(languageString);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }

    public static void requestNotificationPermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        requestCode);
            }
        }
    }

    public static void setupLanguageSpinner(Activity activity, Spinner spinner) {
        String[] languages = {"English", "Russian", "German", "Spanish", "French", "Italian", "Dutch", "Norwegian", "Czech", "Arabic"};
        final String[] languageCodes = {"en", "ru", "de", "es", "fr", "it", "nl", "no", "cs", "ar"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        Preferences prefs = new Preferences(activity);
        String currentLang = prefs.getLocale();
        for (int i = 0; i < languageCodes.length; i++) {
            if (languageCodes[i].equals(currentLang)) {
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initialLanguageLoad = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initialLanguageLoad) {
                    initialLanguageLoad = false;
                    return;
                }
                String selectedLang = languageCodes[position];
                if (!selectedLang.equals(prefs.getLocale())) {
                    prefs.setLocale(selectedLang);
                    activity.recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
