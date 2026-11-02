package com.holdfast.ssh.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

fun newId(): String = UUID.randomUUID().toString()

private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun List<String>.toJson() = JSONArray().also { a -> forEach { a.put(it) } }
private fun JSONArray?.toStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

data class Host(
    val id: String = newId(),
    var label: String = "",
    var address: String = "",
    var port: Int = 22,
    var username: String = "",
    /** "password" or "key" */
    var authType: String = "password",
    var password: String? = null,
    var keyId: String? = null,
    var group: String = "",
    var tags: List<String> = emptyList(),
    var startupCommand: String = "",
    var jumpHostId: String? = null,
    var keepAliveSec: Int = 15,
    var persistentTmux: Boolean = false,
    var logSession: Boolean = false,
    var fontSize: Int = 0,
    var theme: String = "",
    var lastUsed: Long = 0,
    var osHint: String = "",
) {
    val title get() = label.ifBlank { "$username@$address" }

    fun toJson() = JSONObject().apply {
        put("id", id); put("label", label); put("address", address); put("port", port)
        put("username", username); put("authType", authType); put("password", password)
        put("keyId", keyId); put("group", group); put("tags", tags.toJson())
        put("startupCommand", startupCommand); put("jumpHostId", jumpHostId)
        put("keepAliveSec", keepAliveSec); put("persistentTmux", persistentTmux)
        put("logSession", logSession); put("fontSize", fontSize); put("theme", theme)
        put("lastUsed", lastUsed); put("osHint", osHint)
    }

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"), label = o.optString("label"), address = o.optString("address"),
            port = o.optInt("port", 22), username = o.optString("username"),
            authType = o.optString("authType", "password"), password = o.optStr("password"),
            keyId = o.optStr("keyId"), group = o.optString("group"), tags = o.optJSONArray("tags").toStrings(),
            startupCommand = o.optString("startupCommand"), jumpHostId = o.optStr("jumpHostId"),
            keepAliveSec = o.optInt("keepAliveSec", 15), persistentTmux = o.optBoolean("persistentTmux"),
            logSession = o.optBoolean("logSession"), fontSize = o.optInt("fontSize"),
            theme = o.optString("theme"), lastUsed = o.optLong("lastUsed"), osHint = o.optString("osHint"),
        )
    }
}

data class SshKey(
    val id: String = newId(),
    var name: String = "",
    var type: String = "",
    var privateKey: String = "",
    var publicKey: String = "",
    var passphrase: String? = null,
    var created: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type); put("privateKey", privateKey)
        put("publicKey", publicKey); put("passphrase", passphrase); put("created", created)
    }

    companion object {
        fun fromJson(o: JSONObject) = SshKey(
            o.getString("id"), o.optString("name"), o.optString("type"), o.optString("privateKey"),
            o.optString("publicKey"), o.optStr("passphrase"), o.optLong("created"),
        )
    }
}

data class Snippet(
    val id: String = newId(),
    var name: String = "",
    var body: String = "",
    var autoEnter: Boolean = true,
    var tags: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("body", body); put("autoEnter", autoEnter); put("tags", tags.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = Snippet(
            o.getString("id"), o.optString("name"), o.optString("body"), o.optBoolean("autoEnter", true),
            o.optJSONArray("tags").toStrings(),
        )
    }
}

data class PortForward(
    val id: String = newId(),
    var hostId: String = "",
    var label: String = "",
    /** "L" local, "R" remote, "D" dynamic SOCKS */
    var type: String = "L",
    var bindHost: String = "127.0.0.1",
    var bindPort: Int = 8080,
    var destHost: String = "127.0.0.1",
    var destPort: Int = 80,
    var autoStart: Boolean = true,
) {
    fun describe() = when (type) {
        "L" -> "Local $bindHost:$bindPort → $destHost:$destPort"
        "R" -> "Remote :$bindPort → $destHost:$destPort"
        else -> "SOCKS $bindHost:$bindPort"
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("hostId", hostId); put("label", label); put("type", type)
        put("bindHost", bindHost); put("bindPort", bindPort); put("destHost", destHost)
        put("destPort", destPort); put("autoStart", autoStart)
    }

    companion object {
        fun fromJson(o: JSONObject) = PortForward(
            o.getString("id"), o.optString("hostId"), o.optString("label"), o.optString("type", "L"),
            o.optString("bindHost", "127.0.0.1"), o.optInt("bindPort"), o.optString("destHost", "127.0.0.1"),
            o.optInt("destPort"), o.optBoolean("autoStart", true),
        )
    }
}

data class KnownHost(val hostPort: String, var keyType: String, var fingerprint: String) {
    fun toJson() = JSONObject().apply { put("hostPort", hostPort); put("keyType", keyType); put("fingerprint", fingerprint) }

    companion object {
        fun fromJson(o: JSONObject) = KnownHost(o.getString("hostPort"), o.optString("keyType"), o.optString("fingerprint"))
    }
}

/** Everything that gets backed up / synced. */
class VaultData(
    // Copy-on-write: UI threads iterate while connection threads add/remove safely.
    val hosts: MutableList<Host> = CopyOnWriteArrayList(),
    val keys: MutableList<SshKey> = CopyOnWriteArrayList(),
    val snippets: MutableList<Snippet> = CopyOnWriteArrayList(),
    val forwards: MutableList<PortForward> = CopyOnWriteArrayList(),
    val knownHosts: MutableList<KnownHost> = CopyOnWriteArrayList(),
    var gistToken: String = "",
    var gistId: String = "",
    var seeded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("hosts", JSONArray().also { a -> hosts.forEach { a.put(it.toJson()) } })
        put("keys", JSONArray().also { a -> keys.forEach { a.put(it.toJson()) } })
        put("snippets", JSONArray().also { a -> snippets.forEach { a.put(it.toJson()) } })
        put("forwards", JSONArray().also { a -> forwards.forEach { a.put(it.toJson()) } })
        put("knownHosts", JSONArray().also { a -> knownHosts.forEach { a.put(it.toJson()) } })
        put("gistToken", gistToken); put("gistId", gistId); put("seeded", seeded)
    }

    companion object {
        private fun <T> JSONObject.list(k: String, f: (JSONObject) -> T): MutableList<T> {
            val a = optJSONArray(k) ?: return CopyOnWriteArrayList()
            return CopyOnWriteArrayList((0 until a.length()).map { f(a.getJSONObject(it)) })
        }

        fun fromJson(o: JSONObject) = VaultData(
            o.list("hosts", Host::fromJson), o.list("keys", SshKey::fromJson),
            o.list("snippets", Snippet::fromJson), o.list("forwards", PortForward::fromJson),
            o.list("knownHosts", KnownHost::fromJson), o.optString("gistToken"), o.optString("gistId"),
            o.optBoolean("seeded"),
        )
    }
}
