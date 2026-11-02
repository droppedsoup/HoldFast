package com.holdfast.ssh.session

import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.Choreographer
import android.os.Looper
import com.holdfast.ssh.Prefs
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.PortForward
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.data.newId
import com.holdfast.ssh.ssh.AuthCancelled
import com.holdfast.ssh.ssh.Connected
import com.holdfast.ssh.ssh.Connector
import com.holdfast.ssh.ssh.Forwarder
import com.holdfast.ssh.ssh.HostKeyRejected
import com.holdfast.ssh.ssh.Keys
import com.holdfast.ssh.ssh.Remote
import com.holdfast.ssh.util.Backoff
import com.holdfast.ssh.util.Tailnet
import com.termux.terminal.RemoteTerminal
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * One terminal tab. Owns the SSH connection and transparently reconnects (same screen,
 * same scrollback) when the link drops — e.g. after the phone sleeps or switches networks.
 */
class ActiveSession(
    private val ctx: Context,
    hostIn: Host,
    private var setupKeyAfterPassword: Boolean,
    private var cachedPassword: String?,
    private val extraForward: PortForward? = null,
) : TerminalSessionClient {

    enum class State { CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }

    interface Listener {
        fun onScreenUpdated(s: ActiveSession) {}
        fun onStateChanged(s: ActiveSession) {}
        fun onBell(s: ActiveSession) {}
        fun onCopy(s: ActiveSession, text: String) {}
        fun onPasteRequest(s: ActiveSession) {}
    }

    val id = newId()
    var host: Host = hostIn
        private set
    val terminal: TerminalSession = RemoteTerminal.create(80, 24, Prefs.scrollback, this)
    @Volatile var state = State.CONNECTING
        private set
    @Volatile var status = "Connecting…"
        private set
    var tailscaleHint = false
        private set
    val forwards = mutableListOf<Forwarder>()
    var listener: Listener? = null

    // Activity tracking for "task finished" alerts (see DoneNotifier).
    // All transitions happen under actLock, so the 2s tick and the output thread cannot interleave.
    private val actLock = Any()
    private var armedAt = 0L          // when the user last pressed Enter (0 = nothing running)
    private var outputSinceArm = false
    private var lastOutputAt = 0L

    private fun markOutput(now: Long) = synchronized(actLock) {
        lastOutputAt = now
        if (armedAt > 0) outputSinceArm = true
    }

    private fun markEnter(now: Long) = synchronized(actLock) { armedAt = now; outputSinceArm = false }

    /**
     * True exactly once when work started by Enter has produced output and then gone quiet for [idleMs],
     * and it ran at least [minMs] (so quick commands and plain typing never alert).
     */
    fun pollFinished(now: Long, idleMs: Long, minMs: Long): Boolean = synchronized(actLock) {
        if (armedAt == 0L || !outputSinceArm || now - lastOutputAt < idleMs) return false
        outputSinceArm = false
        if (lastOutputAt - armedAt < minMs) return false // just the echo or a quick result: stay armed
        armedAt = 0L
        true
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Object()
    @Volatile private var closed = false
    @Volatile private var conn: Connected? = null
    @Volatile private var shellSession: Session? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var out: OutputStream? = null
    private var cols = 80
    private var rows = 24
    private var log: FileOutputStream? = null
    private val teardownLock = Object()
    /** Keyboard bytes waiting for the network. Unbounded hand-off so the UI never blocks on a slow link. */
    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private val POISON = ByteArray(0)

    private class Cancelled : Exception("closed")

    val title: String get() = host.title

    fun start() {
        thread(isDaemon = true, name = "hf-in-$id") { inputLoop() }
        thread(isDaemon = true, name = "hf-out-$id") { writerLoop() }
        thread(isDaemon = true, name = "hf-conn-$id") { runLoop() }
    }

    // ---- connection lifecycle ----

    private fun runLoop() {
        var attempt = 0
        var everConnected = false
        while (!closed) {
            setState(if (everConnected) State.RECONNECTING else State.CONNECTING,
                if (everConnected) "Reconnecting…" else "Connecting to ${host.address}…")
            try {
                connectOnce(everConnected)
                everConnected = true
                attempt = 0
                setState(State.CONNECTED, "Connected")
                val cleanExit = pumpOutput()
                if (cleanExit || closed) {
                    teardown()
                    if (!closed) {
                        notice("Session ended. Use Reconnect to start a new one.")
                        setState(State.ENDED, "Session ended")
                    }
                    return
                }
            } catch (e: Cancelled) { teardown(); return }
            catch (e: HostKeyRejected) { teardown(); fail(e.message ?: "Host key rejected"); return }
            catch (e: AuthCancelled) { teardown(); fail("Login cancelled"); return }
            catch (e: UserAuthException) {
                teardown()
                if (!everConnected) { fail("Login failed: ${e.message}"); return }
            } catch (e: Exception) {
                teardown()
                if (!everConnected) {
                    tailscaleHint = Tailnet.isTailscale(host.address) && !vpnActive()
                    fail(friendly(e)); return
                }
            }
            teardown()
            if (closed) return
            val wait = Backoff.delayMs(attempt++)
            notice("Connection lost. Reconnecting in ${wait / 1000}s…")
            setState(State.RECONNECTING, "Reconnecting in ${wait / 1000}s")
            synchronized(lock) { if (!closed) lock.wait(wait) }
        }
    }

    private fun connectOnce(isReconnect: Boolean) {
        val c = Connector.open(host, UiPrompter, cachedPassword)
        synchronized(teardownLock) {
            if (closed) { c.close(); throw Cancelled() }
            conn = c
        }
        if (c.usedPassword != null) cachedPassword = c.usedPassword
        if (c.usedPassword != null && setupKeyAfterPassword) {
            setupKeyAfterPassword = false
            setupKeyLogin(c)
        }
        if (host.osHint.isBlank()) {
            host.osHint = try { Remote.detectOs(c.client) } catch (_: Exception) { "" }
            Store.host(host.id)?.let { it.osHint = host.osHint; Store.save() }
        }
        uploadCrashReports(c)
        startForwards(c)
        val s = c.client.startSession()
        s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
        val sh = s.startShell()
        synchronized(teardownLock) {
            if (closed) { try { s.close() } catch (_: Exception) {}; throw Cancelled() }
            shellSession = s; shell = sh; out = sh.outputStream
        }
        outQueue.clear() // drop keystrokes typed while disconnected
        if (host.logSession && log == null) openLog()
        if (isReconnect) {
            if (host.osHint == "windows") notice("Reconnected with a NEW shell. Windows ended the old one when the link dropped. " +
                "To pick Claude back up: ⌘ → Resume last Claude session.")
            else if (host.persistentTmux) notice("Reconnected. Reattaching your tmux session…")
            else notice("Reconnected with a new shell.")
        }
        val cmds = mutableListOf<String>()
        if (host.persistentTmux && host.osHint == "unix") cmds += "tmux new -A -s holdfast"
        if (host.startupCommand.isNotBlank()) cmds += host.startupCommand
        if (cmds.isNotEmpty()) {
            Thread.sleep(400)
            cmds.forEach { send((it + "\r").toByteArray()) }
        }
        Store.host(host.id)?.let { it.lastUsed = System.currentTimeMillis(); Store.save() }
    }

    /** First-time setup: replace password login with a fresh Ed25519 key, then verify it works. */
    private fun setupKeyLogin(c: Connected) {
        try {
            notice("Setting up key login so you won't need the password again…")
            val key = Keys.generate("ed25519", "holdfast-${Build.MODEL}".replace(' ', '-'))
            val (ok, os, msg) = Remote.installKey(c.client, key.publicKey, host.osHint)
            host.osHint = os
            if (!ok) { notice("Key setup failed: $msg"); return }
            Store.upsertKey(key.copy(name = "HoldFast ${Build.MODEL}"))
            val test = host.copy(authType = "key", keyId = key.id, password = null)
            try {
                Connector.open(test, UiPrompter, null).close()
            } catch (e: Exception) {
                notice("Key was added but the server didn't accept it yet (${e.message}). Still using the password.")
                return
            }
            host = test
            Store.upsertHost(Store.host(host.id)?.copy(authType = "key", keyId = key.id, password = null, osHint = os) ?: test)
            notice("Key login is set up. Next time you can connect with one tap.")
        } catch (e: Exception) {
            notice("Key setup failed: ${e.message}")
        }
    }

    private fun uploadCrashReports(c: Connected) {
        val files = com.holdfast.ssh.CrashLog.unsent()
        if (files.isEmpty()) return
        try {
            c.client.newSFTPClient().use { s ->
                val home = s.canonicalize(".")
                val dir = (if (home.endsWith("/")) home else "$home/") + com.holdfast.ssh.ssh.Uploader.DIR
                if (s.statExistence(dir) == null) s.mkdir(dir)
                files.forEach { f ->
                    s.open("$dir/holdfast-${f.nameWithoutExtension}.txt",
                        java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC)
                    ).use { rf -> rf.RemoteFileOutputStream().use { it.write(f.readBytes()) } }
                    com.holdfast.ssh.CrashLog.markSent(f)
                }
            }
        } catch (_: Exception) {}
    }

    private fun startForwards(c: Connected) {
        val rules = Store.data.forwards.filter { it.hostId == host.id && it.autoStart }.toMutableList()
        if (extraForward != null && rules.none { it.id == extraForward.id }) rules += extraForward
        rules.forEach { r -> Forwarder(c.client, r).also { it.start(); forwards += it } }
    }

    /** Reads until the channel closes. Returns true if the remote shell exited on its own. */
    private fun pumpOutput(): Boolean {
        val sh = shell ?: return false
        val buf = ByteArray(32 * 1024)
        try {
            val inp = sh.inputStream
            while (true) {
                val n = inp.read(buf)
                if (n < 0 || closed) break
                val copy = buf.copyOf(n)
                markOutput(System.currentTimeMillis())
                log?.let { try { it.write(copy) } catch (_: Exception) {} }
                main.post {
                    RemoteTerminal.feed(terminal, copy, copy.size)
                    requestRedraw()
                }
            }
        } catch (e: Exception) {
            return false
        }
        // EOF: a clean exit carries an exit status; a dropped link does not.
        return try {
            // sshj's shell channel also implements Command, which exposes exit-status/exit-signal.
            val cmd = sh as? Session.Command ?: return false
            cmd.join(2, java.util.concurrent.TimeUnit.SECONDS)
            cmd.exitStatus != null || cmd.exitSignal != null
        } catch (_: Exception) { false }
    }

    private fun inputLoop() {
        val buf = ByteArray(4096)
        while (true) {
            val n = RemoteTerminal.readInput(terminal, buf)
            if (n < 0) return
            send(buf.copyOf(n))
        }
    }

    private fun send(bytes: ByteArray) {
        if (out == null || outQueue.size > 4096) return // disconnected or hopelessly backed up: drop
        if (bytes.contains(13)) markEnter(System.currentTimeMillis())
        outQueue.offer(bytes)
    }

    private fun writerLoop() {
        while (true) {
            val b = outQueue.take()
            if (b === POISON) return
            val o = out ?: continue
            try { o.write(b); o.flush() } catch (_: Exception) { conn?.close() }
        }
    }

    private fun teardown() = synchronized(teardownLock) {
        forwards.forEach { it.stop() }; forwards.clear()
        out = null; shell = null
        try { shellSession?.close() } catch (_: Exception) {}
        shellSession = null
        conn?.close(); conn = null
    }

    private fun fail(msg: String) {
        notice(msg)
        setState(State.FAILED, msg)
    }

    // ---- public controls ----

    fun write(text: String) { terminal.write(text) }

    /** Retry now (network came back, or user tapped Reconnect). */
    fun kick() {
        when (state) {
            State.RECONNECTING -> synchronized(lock) { lock.notifyAll() }
            State.ENDED, State.FAILED -> if (!closed) {
                tailscaleHint = false
                Store.host(host.id)?.let { host = it.copy() }
                thread(isDaemon = true, name = "hf-conn-$id") { runLoop() }
            }
            else -> {}
        }
    }

    /** Force a fresh connection (e.g. the user suspects a stale link). */
    fun forceReconnect() { bg { conn?.close() } }

    fun resize(c: Int, r: Int) {
        if (c == cols && r == rows) return
        cols = c; rows = r
        bg { try { shell?.changeWindowDimensions(c, r, 0, 0) } catch (_: Exception) {} }
    }

    fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        thread(isDaemon = true) { teardown() }
        io.shutdown()
        outQueue.offer(POISON)
        RemoteTerminal.closeInput(terminal)
        try { log?.close() } catch (_: Exception) {}
        listener = null
    }

    // ---- helpers ----

    /** Main thread only. Coalesces bursts of output into one repaint per display frame (smooth scrolling). */
    private var redrawPending = false
    private fun requestRedraw() {
        if (redrawPending) return
        redrawPending = true
        Choreographer.getInstance().postFrameCallback {
            redrawPending = false
            listener?.onScreenUpdated(this)
        }
    }

    private fun bg(f: () -> Unit) { try { io.execute(f) } catch (_: Exception) {} }

    private fun notice(msg: String) {
        val bytes = "\r\n\u001b[1;36m[HoldFast]\u001b[0m $msg\r\n".toByteArray()
        main.post { RemoteTerminal.feed(terminal, bytes, bytes.size); requestRedraw() }
    }

    private fun setState(s: State, msg: String) {
        state = s; status = msg
        main.post { listener?.onStateChanged(this); SessionManager.notifyChanged() }
    }

    private fun vpnActive(): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is java.net.UnknownHostException -> "Can't find ${host.address}."
            e is java.net.SocketTimeoutException || m.contains("timed out", true) -> "Timed out reaching ${host.address}."
            e is java.net.ConnectException -> "Connection refused by ${host.address}:${host.port}."
            else -> "Connection failed: $m"
        }
    }

    private fun openLog() {
        try {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            log = FileOutputStream(File(dir, "${host.title.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$stamp.log"))
        } catch (_: Exception) {}
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) { requestRedraw() }
    override fun onTitleChanged(changedSession: TerminalSession) { listener?.onStateChanged(this) }
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { text?.let { listener?.onCopy(this, it) } }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { listener?.onPasteRequest(this) }
    override fun onBell(session: TerminalSession) { listener?.onBell(this); DoneNotifier.onBell(this) }
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
