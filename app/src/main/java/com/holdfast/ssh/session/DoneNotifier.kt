package com.holdfast.ssh.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.holdfast.ssh.Prefs
import com.holdfast.ssh.R
import com.holdfast.ssh.ui.TerminalActivity

/**
 * "It's done" alerts. Pressing Enter arms a tab. If the work it started (e.g. Claude, a build) runs
 * for a while and then goes quiet, or the terminal rings its bell, and you're not looking at that tab,
 * the phone vibrates and shows a notification. Typing without Enter, or quick commands, never alert.
 */
object DoneNotifier {
    private const val CHANNEL = "done"
    /** Bursts shorter than this (typing echo, quick commands) never alert. */
    private const val MIN_BUSY_MS = 4_000L

    /** Session currently on screen in TerminalActivity (null when it isn't in front). */
    @Volatile var visibleSessionId: String? = null
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val ch = NotificationChannel(CHANNEL, "Task finished", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A terminal tab finished working and is waiting for you"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun watching(s: ActiveSession): Boolean =
        visibleSessionId == s.id && app.getSystemService(PowerManager::class.java).isInteractive

    /** Called every couple of seconds by the service. */
    fun tick(sessions: List<ActiveSession>) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        val idleMs = Prefs.doneIdleSec * 1000L
        for (s in sessions) {
            if (s.pollFinished(now, idleMs, MIN_BUSY_MS) && Prefs.notifyDone && !watching(s)) {
                notify(s, "is done or waiting for you")
            }
        }
    }

    fun onBell(s: ActiveSession) {
        if (::app.isInitialized && Prefs.notifyDone && !watching(s)) notify(s, "needs your attention")
    }

    fun clear(s: ActiveSession) {
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(s.id.hashCode())
    }

    private fun notify(s: ActiveSession, what: String) {
        val lastLine = try {
            s.terminal.emulator?.screen?.transcriptText?.lines()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        } catch (_: Exception) { null }
        val open = PendingIntent.getActivity(
            app, s.id.hashCode(),
            Intent(app, TerminalActivity::class.java).putExtra("session", s.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_holdfast)
            .setContentTitle("✓ ${s.title} $what")
            .setContentText(lastLine?.take(120) ?: "Tap to open")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lastLine?.take(400) ?: "Tap to open"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(longArrayOf(0, 180, 120, 180))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { app.getSystemService(NotificationManager::class.java).notify(s.id.hashCode(), n) } catch (_: SecurityException) {}
    }
}
