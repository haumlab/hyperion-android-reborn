package com.hyperion.grabber.tv.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.hyperion.grabber.R;
import com.hyperion.grabber.common.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Initial setup wizard that combines language selection and Hyperion server setup.
 * This is shown on first launch before NetworkScanActivity.
 */
public class SetupWizardActivity extends LeanbackActivity {

    public static final int RESULT_SETUP_COMPLETE = 100;
    private static final int REQUEST_NETWORK_SCAN = 1;

    public static Intent createIntent(Context context) {
        return new Intent(context, SetupWizardActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, new WelcomeStepFragment(), android.R.id.content);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_NETWORK_SCAN) {
            if (resultCode == RESULT_OK) {
                setResult(RESULT_SETUP_COMPLETE);
                finish();
            }
        }
    }

    /**
     * Step 1: Welcome screen with language selection
     */
    public static class WelcomeStepFragment extends GuidedStepSupportFragment {

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
                    getString(R.string.setup_welcome_title),
                    getString(R.string.setup_welcome_description),
                    getString(R.string.app_name),
                    null
            );
        }

        @Override
        public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
            // Get current language to show as selected
            Preferences prefs = new Preferences(requireContext());
            String currentLang = prefs.getSelectedLanguage();

            actions.add(createLanguageAction(ID_ENGLISH, "English", "English", "en".equals(currentLang)));
            actions.add(createLanguageAction(ID_GERMAN, "Deutsch", "German", "de".equals(currentLang)));
            actions.add(createLanguageAction(ID_FRENCH, "Français", "French", "fr".equals(currentLang)));
            actions.add(createLanguageAction(ID_SPANISH, "Español", "Spanish", "es".equals(currentLang)));
            actions.add(createLanguageAction(ID_ITALIAN, "Italiano", "Italian", "it".equals(currentLang)));
            actions.add(createLanguageAction(ID_DUTCH, "Nederlands", "Dutch", "nl".equals(currentLang)));
            actions.add(createLanguageAction(ID_RUSSIAN, "Русский", "Russian", "ru".equals(currentLang)));
            actions.add(createLanguageAction(ID_ARABIC, "العربية", "Arabic", "ar".equals(currentLang)));
            actions.add(createLanguageAction(ID_NORWEGIAN, "Norsk", "Norwegian", "no".equals(currentLang)));
            actions.add(createLanguageAction(ID_CZECH, "Čeština", "Czech", "cs".equals(currentLang)));
        }

        private GuidedAction createLanguageAction(long id, String title, String description, boolean checked) {
            return new GuidedAction.Builder(getContext())
                    .id(id)
                    .title(title)
                    .description(description)
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                    .checked(checked)
                    .build();
        }

        @Override
        public void onCreateButtonActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
            actions.add(new GuidedAction.Builder(getContext())
                    .id(GuidedAction.ACTION_ID_CONTINUE)
                    .title(getString(R.string.next))
                    .build());
        }

        @Override
        public void onGuidedActionClicked(GuidedAction action) {
            long id = action.getId();
            
            // Handle "Continue" button
            if (id == GuidedAction.ACTION_ID_CONTINUE) {
                // Mark language as selected
                Preferences prefs = new Preferences(requireContext());
                prefs.setLanguageSelected(true);
                
                // Proceed to network scan
                Intent intent = new Intent(getActivity(), NetworkScanActivity.class);
                getActivity().startActivityForResult(intent, REQUEST_NETWORK_SCAN);
                return;
            }
            
            // Handle language selection
            String languageCode = getLanguageCode(id);
            
            if (languageCode != null) {
                // Update selection visually
                for (int i = 0; i < getActions().size(); i++) {
                    GuidedAction a = getActions().get(i);
                    a.setChecked(a.getId() == id);
                    notifyActionChanged(i);
                }
                
                // Apply language immediately
                setAppLanguage(languageCode);
                
                // Save selection
                Preferences prefs = new Preferences(requireContext());
                prefs.setSelectedLanguage(languageCode);
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
            
            // Refresh the guidance text
            if (getView() != null) {
                // Update guidance texts with new locale
                requireActivity().recreate();
            }
        }

        @Override
        public int onProvideTheme() {
            return R.style.Theme_HyperionGrabber_GuidedStep;
        }
    }
}
