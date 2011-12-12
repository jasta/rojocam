package org.devtcg.rojocam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import java.util.EnumSet;

public class SettingsActivity extends PreferenceActivity implements OnPreferenceChangeListener {
    private ListPreference mPolicy;
    private EditTextPreference mPassword;

    private final Handler mHandler = new Handler();

    private static final String KEY_POLICY = "policy";
    private static final String KEY_PASSWORD = "password";

    public static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static EnumSet<CameraPolicy> getPolicy(Context context) {
        return CameraPolicy.fromString(getPrefs(context).getString(KEY_POLICY, ""));
    }

    public static void show(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);

        mPolicy = (ListPreference)findPreference(KEY_POLICY);
        mPolicy.setOnPreferenceChangeListener(this);

        mPassword = (EditTextPreference)findPreference(KEY_PASSWORD);
        mPassword.setOnPreferenceChangeListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    private int getPolicyIndex(String policy) {
        String[] values = getResources().getStringArray(R.array.policyValues);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(policy)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown policy=" + policy);
    }

    private void updateState() {

        String password = mPassword.getText();
        if (TextUtils.isEmpty(password)) {
            mPassword.setSummary(R.string.no_password_set);
        } else {
            mPassword.setSummary(R.string.password_protected);
        }

        String policy = mPolicy.getValue();
        int policyIndex = getPolicyIndex(policy);
        String[] policySummaries = getResources().getStringArray(R.array.policySummary);
        mPolicy.setSummary(mPolicy.getEntry() + ": " + policySummaries[policyIndex]);
    }

    private final Runnable mUpdateState = new Runnable() {
        public void run() {
            updateState();
        }
    };

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        System.out.println("onPreferenceChange: preference=" + preference.getKey() +
                "; newValue=" + newValue);
        mHandler.post(mUpdateState);
        return true;
    }
}
