package com.hyperion.grabber.tv.fragments.settings

import androidx.leanback.widget.GuidanceStylist
import com.hyperion.grabber.tv.R

class SettingsStepStylist : GuidanceStylist() {
    override fun onProvideLayoutId(): Int {
        return R.layout.settings_guidance
    }
}