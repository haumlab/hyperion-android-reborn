package com.hyperion.grabber.tv.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.hyperion.grabber.R;
import com.hyperion.grabber.common.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Language selection activity that appears on first launch.
 * Allows user to select their preferred language for the app.
 */
public class LanguageSelectionActivity extends LeanbackActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, LanguageSelectionActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, new LanguageStepFragment(), android.R.id.content);
        }
    }

    public static class LanguageStepFragment extends GuidedStepSupportFragment {

        private static final long ID_ENGLISH = 1;
        private static final long ID_GERMAN = 2;
        private static final long ID_FRENCH = 3;
        private static final long ID_SPANISH = 4;
        private static final long ID_ITALIAN = 5;
        private static final long ID_DUTCH = 6;
        private static final long ID_RUSSIAN = 7;
        private static final long ID_ARABIC = 8;
        private static final long ID_NORWEGIAN = 9;
        private static final long ID_CZECH = 10;

        @Override
        @NonNull
        public GuidanceStylist.Guidance onCreateGuidance(@NonNull Bundle savedInstanceState) {
            return new GuidanceStylist.Guidance(
                    getString(R.string.language_selection_title),
                    getString(R.string.language_selection_description),
                    getString(R.string.app_name),
                    null
            );
        }

        @Override
        public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
            actions.add(createLanguageAction(ID_ENGLISH, "English", "English"));
            actions.add(createLanguageAction(ID_GERMAN, "Deutsch", "German"));
            actions.add(createLanguageAction(ID_FRENCH, "Français", "French"));
            actions.add(createLanguageAction(ID_SPANISH, "Español", "Spanish"));
            actions.add(createLanguageAction(ID_ITALIAN, "Italiano", "Italian"));
            actions.add(createLanguageAction(ID_DUTCH, "Nederlands", "Dutch"));
            actions.add(createLanguageAction(ID_RUSSIAN, "Русский", "Russian"));
            actions.add(createLanguageAction(ID_ARABIC, "العربية", "Arabic"));
            actions.add(createLanguageAction(ID_NORWEGIAN, "Norsk", "Norwegian"));
            actions.add(createLanguageAction(ID_CZECH, "Čeština", "Czech"));
        }

        private GuidedAction createLanguageAction(long id, String title, String description) {
            return new GuidedAction.Builder(getContext())
                    .id(id)
                    .title(title)
                    .description(description)
                    .build();
        }

        @Override
        public void onGuidedActionClicked(GuidedAction action) {
            String languageCode = getLanguageCode(action.getId());
            if (languageCode != null) {
                setAppLanguage(languageCode);
                
                // Mark first launch complete
                Preferences prefs = new Preferences(requireContext());
                prefs.setLanguageSelected(true);
                prefs.setSelectedLanguage(languageCode);

                // Navigate to main activity
                Intent intent = new Intent(getActivity(), MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                requireActivity().finish();
            }
        }

        private String getLanguageCode(long actionId) {
            if (actionId == ID_ENGLISH) return "en";
            if (actionId == ID_GERMAN) return "de";
            if (actionId == ID_FRENCH) return "fr";
            if (actionId == ID_SPANISH) return "es";
            if (actionId == ID_ITALIAN) return "it";
            if (actionId == ID_DUTCH) return "nl";
            if (actionId == ID_RUSSIAN) return "ru";
            if (actionId == ID_ARABIC) return "ar";
            if (actionId == ID_NORWEGIAN) return "no";
            if (actionId == ID_CZECH) return "cs";
            return null;
        }

        private void setAppLanguage(String languageCode) {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);
            
            Configuration config = new Configuration(getResources().getConfiguration());
            config.setLocale(locale);
            
            requireContext().getResources().updateConfiguration(config,
                    requireContext().getResources().getDisplayMetrics());
        }

        @Override
        public int onProvideTheme() {
            return R.style.Theme_HyperionGrabber_GuidedStep;
        }
    }
}
