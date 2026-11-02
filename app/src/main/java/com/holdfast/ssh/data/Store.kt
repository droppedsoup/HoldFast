package com.holdfast.ssh.data

import android.content.Context
import com.holdfast.ssh.crypto.KeystoreCipher
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Encrypted local vault (hosts, keys, snippets, forwards, known hosts). */
object Store {
    @Volatile lateinit var data: VaultData
        private set
    private lateinit var file: File
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "vault.bin")
        data = try {
            if (file.exists()) VaultData.fromJson(JSONObject(String(KeystoreCipher.decrypt(file.readBytes()))))
            else VaultData()
        } catch (e: Exception) {
            // Keystore key lost (e.g. restored to new phone): keep the unreadable file aside, start fresh.
            file.renameTo(File(ctx.filesDir, "vault.unreadable.${System.currentTimeMillis()}"))
            VaultData()
        }
        if (!data.seeded) seed()
    }

    private fun seed() {
        data.snippets += Snippet(name = "Resume last Claude session", body = "claude --continue")
        data.snippets += Snippet(name = "Claude Code", body = "claude")
        data.snippets += Snippet(name = "Go to folder", body = "cd {{folder}}")
        data.snippets += Snippet(name = "Git status", body = "git status")
        data.seeded = true
        save()
    }

    private val lock = Any()

    /** Persists a consistent snapshot; listeners run after the lock is released. */
    fun save() {
        synchronized(lock) {
            val tmp = File(file.parentFile, "vault.tmp")
            tmp.writeBytes(KeystoreCipher.encrypt(data.toJson().toString().toByteArray()))
            if (!tmp.renameTo(file)) {
                file.delete(); tmp.renameTo(file)
            }
        }
        listeners.forEach { it() }
    }

    /** Runs a multi-step mutation atomically, then saves. */
    private inline fun mutate(block: () -> Unit) { synchronized(lock) { block() }; save() }

    fun replaceAll(newData: VaultData) = mutate {
        newData.seeded = true
        data = newData
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun host(id: String?) = data.hosts.firstOrNull { it.id == id }
    fun key(id: String?) = data.keys.firstOrNull { it.id == id }

    fun upsertHost(h: Host) = mutate { data.hosts.removeAll { it.id == h.id }; data.hosts += h }
    fun deleteHost(id: String) = mutate {
        data.hosts.removeAll { it.id == id }
        data.forwards.removeAll { it.hostId == id }
        data.hosts.filter { it.jumpHostId == id }.forEach { it.jumpHostId = null }
    }
    fun upsertKey(k: SshKey) = mutate { data.keys.removeAll { it.id == k.id }; data.keys += k }
    fun deleteKey(id: String) = mutate {
        data.keys.removeAll { it.id == id }
        data.hosts.filter { it.keyId == id }.forEach { it.keyId = null; it.authType = "password" }
    }
    fun upsertSnippet(s: Snippet) = mutate { data.snippets.removeAll { it.id == s.id }; data.snippets += s }
    fun deleteSnippet(id: String) = mutate { data.snippets.removeAll { it.id == id } }
    fun upsertForward(f: PortForward) = mutate { data.forwards.removeAll { it.id == f.id }; data.forwards += f }
    fun deleteForward(id: String) = mutate { data.forwards.removeAll { it.id == id } }

    fun knownHost(hostPort: String) = data.knownHosts.firstOrNull { it.hostPort == hostPort }
    fun trustHost(hostPort: String, type: String, fp: String) = mutate {
        data.knownHosts.removeAll { it.hostPort == hostPort }
        data.knownHosts += KnownHost(hostPort, type, fp)
    }
}
