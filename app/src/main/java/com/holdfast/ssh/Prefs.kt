package com.holdfast.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {
    const val DEFAULT_KEYS = "ESC,TAB,STAB,CTRL,ALT,UP,DOWN,LEFT,RIGHT,|,~,/,-,HOME,END,PGUP,PGDN"
    const val FN_KEYS = "F1,F2,F3,F4,F5,F6,F7,F8,F9,F10,F11,F12"

    private lateinit var p: SharedPreferences
    fun init(ctx: Context) { p = PreferenceManager.getDefaultSharedPreferences(ctx) }

    val keepAwake get() = p.getBoolean("keep_awake", true)
    val keepScreenOn get() = p.getBoolean("keep_screen_on", false)
    val scrollback get() = (p.getString("scrollback", "10000")?.toIntOrNull() ?: 10000).coerceIn(500, 50000)
    var fontSize: Int
        get() = p.getInt("font_size", 14)
        set(v) = p.edit().putInt("font_size", v.coerceIn(6, 40)).apply()
    val theme get() = p.getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT
    val extraKeys get() = (p.getString("extra_keys", DEFAULT_KEYS) ?: DEFAULT_KEYS).ifBlank { DEFAULT_KEYS }
    val volDownCtrl get() = p.getBoolean("vol_down_ctrl", true)
    val charInput get() = p.getBoolean("char_input", true)
    val imeAutocorrect get() = p.getBoolean("ime_autocorrect", true)
    val lockEnabled get() = p.getBoolean("lock_enabled", false)
    val lockTimeoutMin get() = p.getString("lock_timeout", "5")?.toIntOrNull() ?: 5
    val secureScreen get() = p.getBoolean("secure_screen", false)
    val notifyDone get() = p.getBoolean("notify_done", true)
    val doneIdleSec get() = (p.getString("done_idle_sec", "6")?.toIntOrNull() ?: 6).coerceIn(2, 120)
    val vibrateBell get() = p.getBoolean("bell_vibrate", true)
    val tapActions get() = p.getBoolean("tap_actions", true)
    var askedBattery: Boolean
        get() = p.getBoolean("asked_battery", false)
        set(v) = p.edit().putBoolean("asked_battery", v).apply()
}
