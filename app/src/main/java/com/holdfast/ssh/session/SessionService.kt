package com.holdfast.ssh.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.holdfast.ssh.Prefs
import com.holdfast.ssh.R
import com.holdfast.ssh.ui.TerminalActivity

/**
 * Foreground service that keeps SSH sessions alive while the phone is locked:
 * a partial wake lock keeps the CPU running keep-alives and reads (effective in Doze only with the
 * battery exemption), a best-effort Wi-Fi lock (Android limits its effect while the screen is off),
 * and a network callback that triggers instant reconnects when connectivity returns.
 */
class SessionService : Service() {
    companion object {
        const val ACTION_REFRESH = "refresh"
        const val ACTION_DISCONNECT_ALL = "disconnect_all"
        private const val CHANNEL = "sessions"
        private const val NOTIF_ID = 1
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { DoneNotifier.tick(SessionManager.sessions.toList()); ticker.postDelayed(this, 2000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active sessions", NotificationManager.IMPORTANCE_LOW))
        val cm = getSystemService(ConnectivityManager::class.java)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { SessionManager.onNetworkAvailable() }
        }.also { cm.registerDefaultNetworkCallback(it) }
        ticker.postDelayed(tick, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) SessionManager.closeAll()
        val n = SessionManager.sessions.size
        if (n == 0) {
            // A startForegroundService() call must be answered with startForeground() even if we stop at once.
            if (!started && intent?.action == null) startFg()
            stop(); return START_NOT_STICKY
        }
        if (!startFg()) { stopSelf(); return START_NOT_STICKY }
        updateLocks()
        return START_STICKY
    }

    private fun startFg(): Boolean = try {
        val notif = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        started = true
        true
    } catch (e: Exception) {
        started
    }

    private fun buildNotification(): Notification {
        val ss = SessionManager.sessions
        val up = ss.count { it.state == ActiveSession.State.CONNECTED }
        val text = when {
            ss.isEmpty() -> "No sessions"
            ss.size == 1 -> "${ss[0].title} · ${ss[0].status}"
            else -> "$up of ${ss.size} sessions connected"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kill = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_holdfast)
            .setContentTitle("HoldFast")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect all", kill)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateLocks() {
        if (Prefs.keepAwake) {
            if (wake == null) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HoldFast:sessions").apply { setReferenceCounted(false); acquire() }
            if (wifi == null) {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(mode, "HoldFast:sessions").apply { setReferenceCounted(false); acquire() }
            }
        } else releaseLocks()
    }

    private fun releaseLocks() {
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }

    private fun stop() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        releaseLocks()
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
