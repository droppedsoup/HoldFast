package com.holdfast.ssh.ssh

import com.holdfast.ssh.util.shQuote
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Remote helpers: OS detection and authorized_keys installation (Unix + Windows OpenSSH). */
object Remote {
    data class ExecResult(val out: String, val err: String, val status: Int?)

    /** Drains stdout and stderr concurrently under one overall deadline. */
    fun exec(client: SSHClient, cmd: String, timeoutSec: Long = 30): ExecResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        fun left() = (deadline - System.nanoTime()).coerceAtLeast(1)
        val sessionRef = java.util.concurrent.atomic.AtomicReference<net.schmizz.sshj.connection.channel.direct.Session?>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            // Channel open + exec run under the same deadline as the reads.
            val cmdF = pool.submit<net.schmizz.sshj.connection.channel.direct.Session.Command> {
                val s = client.startSession(); sessionRef.set(s); s.exec(cmd)
            }
            val c = cmdF.get(left(), TimeUnit.NANOSECONDS)
            val out = pool.submit<String> { IOUtils.readFully(c.inputStream).toString(Charsets.UTF_8) }
            val err = pool.submit<String> { IOUtils.readFully(c.errorStream).toString(Charsets.UTF_8) }
            val o = out.get(left(), TimeUnit.NANOSECONDS)
            val e = err.get(left(), TimeUnit.NANOSECONDS)
            c.join(left(), TimeUnit.NANOSECONDS)
            return ExecResult(o, e, c.exitStatus)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw java.io.IOException("Remote command timed out after ${timeoutSec}s")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            // Close off-thread: with no transport timeout, a dead server could block close() forever.
            sessionRef.get()?.let { s -> Thread { try { s.close() } catch (_: Exception) {} }.apply { isDaemon = true }.start() }
            pool.shutdownNow()
        }
    }

    /** "windows" or "unix". Git-for-Windows' uname (MINGW/MSYS) still counts as Windows. */
    fun detectOs(client: SSHClient): String {
        val r = try { exec(client, "uname -s", 10) } catch (e: Exception) { return "windows" }
        val o = r.out.trim().uppercase()
        return if (r.status == 0 && o.isNotEmpty() && !o.startsWith("MINGW") && !o.startsWith("MSYS") &&
            !o.startsWith("CYGWIN") && !o.contains("WINDOWS")
        ) "unix" else "windows"
    }

    fun windowsInstallScript(pubLine: String): String {
        val k = pubLine.trim().replace("'", "''")
        return """
${'$'}ErrorActionPreference = 'Stop'
${'$'}k = '$k'
${'$'}body = (${'$'}k -split ' ')[1]
${'$'}targets = @("${'$'}env:USERPROFILE\.ssh\authorized_keys")
if ((whoami /groups) -match 'S-1-5-32-544') { ${'$'}targets = @("${'$'}env:ProgramData\ssh\administrators_authorized_keys") + ${'$'}targets }
${'$'}done = @()
foreach (${'$'}f in ${'$'}targets) {
  try {
    ${'$'}d = Split-Path ${'$'}f
    if (!(Test-Path ${'$'}d)) { New-Item -ItemType Directory -Force ${'$'}d | Out-Null }
    ${'$'}cur = if (Test-Path ${'$'}f) { [IO.File]::ReadAllText(${'$'}f) } else { '' }
    if (${'$'}cur.IndexOf(${'$'}body) -lt 0) {
      if (${'$'}cur.Length -gt 0 -and -not ${'$'}cur.EndsWith("`n")) { ${'$'}cur += "`r`n" }
      [IO.File]::WriteAllText(${'$'}f, ${'$'}cur + ${'$'}k + "`r`n", (New-Object Text.ASCIIEncoding))
    }
    if (${'$'}f -like '*administrators_authorized_keys') { icacls ${'$'}f /inheritance:r /grant '*S-1-5-32-544:F' /grant '*S-1-5-18:F' | Out-Null }
    ${'$'}done += ${'$'}f
  } catch { Write-Output ("FAIL " + ${'$'}f + ": " + ${'$'}_.Exception.Message) }
}
Write-Output ("DONE " + (${'$'}done -join ';'))
""".trim()
    }

    fun encodedPowerShell(script: String): String =
        "powershell -NoProfile -NonInteractive -EncodedCommand " +
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    /** Appends [pubLine] to the right authorized_keys file(s). Returns (success, os, message). */
    fun installKey(client: SSHClient, pubLine: String, osHint: String = ""): Triple<Boolean, String, String> {
        val os = osHint.ifBlank { detectOs(client) }
        return if (os == "windows") {
            val r = exec(client, encodedPowerShell(windowsInstallScript(pubLine)), 60)
            val done = r.out.lines().firstOrNull { it.startsWith("DONE ") }?.removePrefix("DONE ")?.trim().orEmpty()
            Triple(done.isNotEmpty(), os, if (done.isNotEmpty()) "Added to $done" else (r.out + r.err).trim().ifEmpty { "PowerShell failed" })
        } else {
            val line = pubLine.trim()
            val body = line.split(" ").getOrElse(1) { line }
            val cmd = "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
                "(grep -qF ${shQuote(body)} ~/.ssh/authorized_keys || echo ${shQuote(line)} >> ~/.ssh/authorized_keys) && " +
                "chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && echo HF_OK"
            val r = exec(client, cmd)
            val ok = r.out.contains("HF_OK")
            Triple(ok, os, if (ok) "Added to ~/.ssh/authorized_keys" else (r.out + r.err).trim())
        }
    }
}
