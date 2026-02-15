package com.hyperion.grabber.tv.activities;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import com.hyperion.grabber.common.HyperionActivityHelper;

public abstract class LeanbackActivity extends FragmentActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(HyperionActivityHelper.updateBaseContextLocale(newBase));
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }
}
