package com.holdfast.ssh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.session.SessionManager
import com.holdfast.ssh.ui.LockActivity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HoldFastApp : Application() {
    companion object {
        /** When the app last went to the background (for auto-lock). 0 = locked/never unlocked. */
        @Volatile var backgroundedAt = 0L
        @Volatile var unlocked = false
        private var started = 0

        /** True when the app lock is off, or was passed recently enough. */
        fun isUnlocked(): Boolean = !Prefs.lockEnabled ||
            (unlocked && (started > 0 || System.currentTimeMillis() - backgroundedAt <= Prefs.lockTimeoutMin * 60_000L))
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Android ships a stripped "BC" provider; replace it with full BouncyCastle for sshj.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Prefs.init(this)
        Store.init(this)
        SessionManager.init(this)
        com.holdfast.ssh.session.DoneNotifier.init(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && Prefs.lockEnabled && a !is LockActivity && a !is com.holdfast.ssh.ui.ShareActivity) {
                    val idleMs = System.currentTimeMillis() - backgroundedAt
                    if (!unlocked || idleMs > Prefs.lockTimeoutMin * 60_000L) {
                        unlocked = false
                        a.startActivity(android.content.Intent(a, LockActivity::class.java))
                    }
                }
            }
            override fun onActivityStopped(a: Activity) {
                if (--started == 0) backgroundedAt = System.currentTimeMillis()
            }
            override fun onActivityResumed(a: Activity) {
                if (Prefs.secureScreen) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
