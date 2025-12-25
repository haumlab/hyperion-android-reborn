package com.hyperion.grabber.tv.fragments.settings

import androidx.leanback.widget.GuidanceStylist
import com.hyperion.grabber.R

class SettingsStepStylist : GuidanceStylist() {
    override fun onProvideLayoutId(): Int {
        return R.layout.settings_guidance
    }
}