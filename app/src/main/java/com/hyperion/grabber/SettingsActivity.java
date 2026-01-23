package com.hyperion.grabber;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import androidx.appcompat.app.ActionBar;
import android.view.MenuItem;
import android.widget.Toast;
import java.lang.reflect.Field;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
public class SettingsActivity extends AppCompatPreferenceActivity {
    public static final String EXTRA_SHOW_TOAST_KEY = "extra_show_toast_key";
    public static final int EXTRA_SHOW_TOAST_SETUP_REQUIRED_FOR_QUICK_TILE = 1;


    /**
     * A preference value change listener that updates the preference's summary
     * to reflect its new value.
     */
    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = (preference, value) -> {
        final int prefResourceID = getResourceId(preference.getKey(), com.hyperion.grabber.common.R.string.class);

        // Handle connection type change - auto-set port and update summary
        if (prefResourceID == com.hyperion.grabber.common.R.string.pref_key_connection_type) {
            String connectionType = value.toString();
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(preference.getContext());
            android.preference.PreferenceManager prefManager = preference.getPreferenceManager();
            android.preference.EditTextPreference portPref = null;
            if (prefManager != null) {
                portPref = (android.preference.EditTextPreference) prefManager.findPreference(
                    preference.getContext().getString(com.hyperion.grabber.common.R.string.pref_key_port));
            }
            
            // Auto-set port based on connection type
            int defaultPort;
            if ("wled".equals(connectionType)) {
                defaultPort = 19446; // WLED DRGB port
            } else if ("adalight".equals(connectionType)) {
                defaultPort = 0; // Not used for Adalight
            } else {
                defaultPort = 19400; // Hyperion default
            }
            
            if (portPref != null && defaultPort > 0) {
                String currentPort = prefs.getString(preference.getContext().getString(
                    com.hyperion.grabber.common.R.string.pref_key_port), "");
                // Only auto-set if port is empty or is one of the default ports
                if (currentPort.isEmpty() || currentPort.equals("19400") || currentPort.equals("19446") || currentPort.equals("21324")) {
                    portPref.setText(String.valueOf(defaultPort));
                    portPref.setSummary(String.valueOf(defaultPort));
                    // Save to preferences
                    prefs.edit().putString(preference.getContext().getString(
                        com.hyperion.grabber.common.R.string.pref_key_port), String.valueOf(defaultPort)).apply();
                }
            }
            
            // Update summary to show current selection
            android.preference.ListPreference listPref = (android.preference.ListPreference) preference;
            int index = listPref.findIndexOfValue(connectionType);
            if (index >= 0) {
                preference.setSummary(listPref.getEntries()[index]);
            }
            return true;
        }

        // verify we have a valid int value for the following preference keys
        if (prefResourceID == com.hyperion.grabber.common.R.string.pref_key_port ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_reconnect_delay ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_priority ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_x_led ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_y_led ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_framerate ||
            prefResourceID == com.hyperion.grabber.common.R.string.pref_key_adalight_baudrate) {
            try {
                Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return false;
            }
        }

        // Handle ListPreference summary update
        if (preference instanceof android.preference.ListPreference) {
            android.preference.ListPreference listPref = (android.preference.ListPreference) preference;
            int index = listPref.findIndexOfValue(value.toString());
            if (index >= 0) {
                preference.setSummary(listPref.getEntries()[index]);
            }
        } else {
            String stringValue = value.toString();
            preference.setSummary(stringValue);
        }

        return true;
    };

    /**
     * Returns the resource ID of the provided string
     */
    public static int getResourceId(String resourceName, Class<?> c) {
        try {
            Field idField = c.getDeclaredField(resourceName);
            return idField.getInt(idField);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     *
     * @see #sBindPreferenceSummaryToValueListener
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        if (preference == null) return;
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new GeneralPreferenceFragment()).commit();

        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey(EXTRA_SHOW_TOAST_KEY)){
            if (extras.getInt(EXTRA_SHOW_TOAST_KEY) == EXTRA_SHOW_TOAST_SETUP_REQUIRED_FOR_QUICK_TILE){
                Toast.makeText(getApplicationContext(), R.string.quick_tile_toast_setup_required, Toast.LENGTH_SHORT).show();
            }
        }

        setupActionBar();
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    /**
     * This fragment shows general preferences only. It is used when the
     * activity is showing a two-pane settings UI.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class GeneralPreferenceFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(com.hyperion.grabber.common.R.xml.pref_general);
            setHasOptionsMenu(true);

            // Bind the summaries of EditText/List/Dialog/Ringtone preferences
            // to their values. When their values change, their summaries are
            // updated to reflect the new value, per the Android Design
            // guidelines.
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_connection_type)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_host)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_port)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_priority)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_framerate)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_reconnect_delay)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_x_led)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_y_led)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_adalight_baudrate)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_wled_color_order)));
            
            // Setup visibility based on connection type
            setupConnectionTypeDependencies();
        }
        
        private void setupConnectionTypeDependencies() {
            android.preference.ListPreference connectionTypePref = (android.preference.ListPreference) 
                findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_connection_type));
            if (connectionTypePref == null) return;
            
            android.preference.Preference hostPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_host));
            android.preference.Preference portPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_port));
            android.preference.Preference priorityPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_priority));
            android.preference.Preference reconnectPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_reconnect));
            android.preference.Preference reconnectDelayPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_reconnect_delay));
            android.preference.Preference baudratePref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_adalight_baudrate));
            android.preference.Preference wledColorOrderPref = findPreference(getString(com.hyperion.grabber.common.R.string.pref_key_wled_color_order));
            
            Preference.OnPreferenceChangeListener visibilityListener = (preference, newValue) -> {
                String connectionType = newValue.toString();
                boolean isAdalight = "adalight".equals(connectionType);
                boolean isWled = "wled".equals(connectionType);
                boolean isNetwork = "hyperion".equals(connectionType) || isWled;
                
                if (hostPref != null) {
                    hostPref.setEnabled(isNetwork);
                }
                if (portPref != null) {
                    portPref.setEnabled(isNetwork);
                }
                if (priorityPref != null) {
                    priorityPref.setEnabled(isNetwork);
                }
                if (reconnectPref != null) {
                    reconnectPref.setEnabled(isNetwork);
                }
                if (reconnectDelayPref != null) {
                    reconnectDelayPref.setEnabled(isNetwork && reconnectPref != null && reconnectPref.isEnabled());
                }
                if (baudratePref != null) {
                    baudratePref.setEnabled(isAdalight);
                }
                if (wledColorOrderPref != null) {
                    wledColorOrderPref.setEnabled(isWled);
                }
                
                return true;
            };
            
            connectionTypePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean result = sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, newValue);
                if (result) {
                    visibilityListener.onPreferenceChange(preference, newValue);
                }
                return result;
            });
            
            // Set initial visibility
            String currentType = android.preference.PreferenceManager.getDefaultSharedPreferences(getActivity())
                .getString(getString(com.hyperion.grabber.common.R.string.pref_key_connection_type), "hyperion");
            visibilityListener.onPreferenceChange(connectionTypePref, currentType);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }
}
