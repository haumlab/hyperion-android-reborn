package com.hyperion.grabber.tv.activities;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import com.hyperion.grabber.common.util.Preferences;
import java.util.Locale;

public abstract class LeanbackActivity extends FragmentActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        Preferences prefs = new Preferences(newBase);
        String languageString = prefs.getLocale();
        Locale locale = new Locale(languageString);
        Locale.setDefault(locale);

        Resources res = newBase.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }
}
