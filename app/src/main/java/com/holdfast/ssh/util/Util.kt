package com.holdfast.ssh.util

/** Tailscale address helpers (CGNAT 100.64.0.0/10, fd7a:115c:a1e0::/48, *.ts.net MagicDNS). */
object Tailnet {
    fun isTailscale(address: String): Boolean {
        val a = address.trim().lowercase().trimEnd('.')
        if (a.endsWith(".ts.net")) return true
        if (a.startsWith("fd7a:115c:a1e0:")) return true
        val parts = a.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 100 && n[1] in 64..127
    }
}

/** Reconnect delays: 1, 2, 4, 8, 16, 30, 30… seconds. */
object Backoff {
    fun delayMs(attempt: Int): Long {
        val s = if (attempt >= 5) 30L else minOf(30L, 1L shl attempt)
        return s * 1000
    }
}

/** {{name}} placeholders in snippets. */
object SnippetVars {
    private val RX = Regex("""\{\{\s*([A-Za-z0-9_\- ]+?)\s*\}\}""")

    fun names(body: String): List<String> = RX.findAll(body).map { it.groupValues[1] }.distinct().toList()

    fun fill(body: String, values: Map<String, String>): String =
        RX.replace(body) { m -> values[m.groupValues[1]] ?: m.value }
}

/** Single-quote for POSIX shells. */
fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
