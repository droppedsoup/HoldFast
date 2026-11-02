package com.holdfast.ssh.ssh

import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.Store
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.security.PublicKey

/** UI callbacks needed while connecting. All are called on a background thread and may block. */
interface ConnectPrompter {
    /** Return true to trust. [oldFingerprint] is non-null when the key CHANGED. */
    fun confirmHostKey(hostPort: String, keyType: String, fingerprint: String, oldFingerprint: String?): Boolean
    fun askPassword(host: Host): String?
    fun askPassphrase(keyName: String): String?
}

class HostKeyRejected(msg: String) : IOException(msg)
class AuthCancelled : IOException("Login cancelled")

class Connected(val client: SSHClient, private val jump: Connected?, val usedPassword: String?) {
    fun close() {
        try { client.disconnect() } catch (_: Exception) {}
        jump?.close()
    }
}

object Connector {
    private val config by lazy {
        DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
    }

    fun open(
        host: Host,
        prompter: ConnectPrompter,
        cachedPassword: String? = null,
        depth: Int = 0,
    ): Connected {
        if (depth > 4) throw IOException("Jump host chain too long")
        val jump = host.jumpHostId?.let { Store.host(it) }?.let { open(it, prompter, null, depth + 1) }
        val client = SSHClient(config)
        try {
            client.connectTimeout = 15_000
            client.timeout = 0
            client.addHostKeyVerifier(verifier(host, prompter))
            if (jump != null) client.connectVia(jump.client.newDirectConnection(host.address, host.port))
            else client.connect(host.address, host.port)
            client.connection.keepAlive.keepAliveInterval = host.keepAliveSec.coerceIn(0, 300)
            val used = authenticate(client, host, prompter, cachedPassword)
            return Connected(client, jump, used)
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Exception) {}
            jump?.close()
            throw e
        }
    }

    private fun verifier(host: Host, prompter: ConnectPrompter) = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val hp = "${host.address}:${host.port}"
            val fp = Keys.fingerprint(key)
            val type = KeyType.fromKey(key).toString()
            val known = Store.knownHost(hp)
            if (known != null && known.fingerprint == fp) return true
            val ok = prompter.confirmHostKey(hp, type, fp, known?.fingerprint)
            if (!ok) throw HostKeyRejected(if (known == null) "Host key not trusted" else "Host key changed — connection refused")
            Store.trustHost(hp, type, fp)
            return true
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            Store.knownHost("${host.address}:${host.port}")?.let { listOf(it.keyType) } ?: emptyList()
    }

    /** Returns the password if password auth was used (so callers can cache it / offer key setup). */
    private fun authenticate(client: SSHClient, host: Host, prompter: ConnectPrompter, cached: String?): String? {
        val key = if (host.authType == "key") Store.key(host.keyId) else null
        if (key != null) {
            val kp = try {
                Keys.provider(key)
            } catch (e: Keys.NeedsPassphrase) {
                Keys.load(key.privateKey, prompter.askPassphrase(key.name) ?: throw AuthCancelled())
            }
            try {
                client.authPublickey(host.username, kp)
                return null
            } catch (e: UserAuthException) {
                if (host.password == null && cached == null) throw UserAuthException("Key was rejected by the server", e)
            }
        }
        val pw = cached ?: host.password ?: prompter.askPassword(host) ?: throw AuthCancelled()
        try {
            client.authPassword(host.username, pw)
        } catch (e: UserAuthException) {
            // Some servers only offer keyboard-interactive for passwords.
            client.auth(host.username, AuthKeyboardInteractive(PasswordResponseProvider(PasswordUtils.createOneOff(pw.toCharArray()))))
        }
        return pw
    }
}
