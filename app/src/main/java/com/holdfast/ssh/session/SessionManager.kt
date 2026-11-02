package com.holdfast.ssh.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.PortForward
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide list of open terminal tabs. The foreground service keeps the process alive. */
object SessionManager {
    val sessions = CopyOnWriteArrayList<ActiveSession>()
    var currentId: String? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun open(host: Host, setupKey: Boolean = false, password: String? = null, forward: PortForward? = null): ActiveSession {
        val s = ActiveSession(app, host.copy(), setupKey, password, forward)
        sessions += s
        currentId = s.id
        ContextCompat.startForegroundService(app, Intent(app, SessionService::class.java))
        s.start()
        notifyChanged()
        return s
    }

    fun get(id: String?) = sessions.firstOrNull { it.id == id }
    fun current() = get(currentId) ?: sessions.lastOrNull()

    fun close(s: ActiveSession) {
        s.close()
        sessions -= s
        if (currentId == s.id) currentId = sessions.lastOrNull()?.id
        notifyChanged()
    }

    fun closeAll() { sessions.toList().forEach { close(it) } }

    /** Network (or Tailscale tunnel) came back: retry pending reconnects immediately. */
    fun onNetworkAvailable() { sessions.forEach { if (it.state == ActiveSession.State.RECONNECTING) it.kick() } }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() {
        listeners.forEach { it() }
        if (::app.isInitialized) try {
            app.startService(Intent(app, SessionService::class.java).setAction(SessionService.ACTION_REFRESH))
        } catch (_: Exception) {}
    }
}
