package com.holdfast.ssh.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.holdfast.ssh.HoldFastApp

/** App lock using the phone's own fingerprint / face / PIN. */
class LockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@LockActivity).apply { text = "HoldFast is locked"; textSize = 20f; gravity = Gravity.CENTER })
            addView(Button(this@LockActivity).apply { text = "Unlock"; setOnClickListener { prompt() } })
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
        prompt()
    }

    private fun prompt() {
        val p = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                HoldFastApp.unlocked = true
                HoldFastApp.backgroundedAt = System.currentTimeMillis()
                setResult(RESULT_OK)
                finish()
            }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock HoldFast")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build()
        )
    }
}
