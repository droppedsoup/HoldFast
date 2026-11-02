package com.holdfast.ssh.session

import android.os.Handler
import android.os.Looper
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.ssh.ConnectPrompter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Routes connection-time questions (host keys, passwords) from background threads to whichever
 * activity is in front. With no UI attached (e.g. a background reconnect) questions time out → "no".
 */
object UiPrompter : ConnectPrompter {
    sealed class Request {
        val answer = CompletableFuture<String?>()
        class HostKey(val hostPort: String, val keyType: String, val fp: String, val oldFp: String?) : Request()
        class Password(val host: Host) : Request()
        class Passphrase(val keyName: String) : Request()
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = CopyOnWriteArrayList<Request>()
    @Volatile private var handler: ((Request) -> Unit)? = null

    fun attach(h: (Request) -> Unit) {
        handler = h
        main.post { pending.filter { !it.answer.isDone }.forEach(h) }
    }

    fun detach(h: (Request) -> Unit) { if (handler === h) handler = null }

    private fun ask(r: Request): String? {
        pending += r
        main.post { handler?.invoke(r) }
        return try {
            r.answer.get(if (handler == null) 20 else 180, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        } finally {
            pending -= r
        }
    }

    override fun confirmHostKey(hostPort: String, keyType: String, fingerprint: String, oldFingerprint: String?) =
        ask(Request.HostKey(hostPort, keyType, fingerprint, oldFingerprint)) == "yes"

    override fun askPassword(host: Host) = ask(Request.Password(host))
    override fun askPassphrase(keyName: String) = ask(Request.Passphrase(keyName))
}
