package com.holdfast.ssh.ui

import android.os.Bundle
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.holdfast.ssh.R
import com.holdfast.ssh.Themes

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tb.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.content, Frag()).commit()
    }

    class Frag : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<ListPreference>("theme")?.apply {
                entries = Themes.names().toTypedArray(); entryValues = entries
                if (value == null) value = Themes.DEFAULT
            }
            findPreference<Preference>("battery")?.setOnPreferenceClickListener { Launch.requestBattery(requireContext()); true }
            findPreference<Preference>("about")?.summary =
                "HoldFast ${requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName}. " +
                    "Free, open SSH client. Terminal engine: Termux terminal-emulator (Apache-2.0). SSH: sshj (Apache-2.0)."
        }

        override fun onResume() {
            super.onResume()
            val pm = requireContext().getSystemService(PowerManager::class.java)
            findPreference<Preference>("battery")?.summary =
                if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) "Unrestricted ✓ Sessions survive screen-off."
                else "Restricted. Tap to allow HoldFast to run while the phone is locked."
        }
    }
}
