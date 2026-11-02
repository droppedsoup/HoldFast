package com.holdfast.ssh.ssh

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.session.UiPrompter
import net.schmizz.sshj.sftp.OpenMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/** Sends phone photos/files to ~/HoldFast-uploads on a host and returns shell-ready paths. */
object Uploader {
    const val DIR = "HoldFast-uploads"

    fun displayName(ctx: Context, uri: Uri): String {
        val n = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
        val base = (n ?: uri.lastPathSegment ?: "upload").substringAfterLast('/')
        val clean = safeName(base)
        if (clean.contains('.')) return clean
        val ext = ctx.contentResolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext != null) "$clean.$ext" else clean
    }

    /** Allowlist (letters, digits, dot, dash, underscore) so pasted paths are inert in cmd, PowerShell and sh. */
    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').ifEmpty { "upload" }.take(120)

    /**
     * "/C:/Users/me/x.jpg" becomes "C:\Users\me\x.jpg" on Windows hosts. File names are allowlisted, so only
     * the home folder could hold unusual characters; such paths get quoted.
     */
    fun shellPath(remote: String): String {
        val win = Regex("^/[A-Za-z]:/").containsMatchIn(remote)
        val p = if (win) remote.drop(1).replace('/', '\\') else remote
        if (Regex("""^[A-Za-z0-9._/\\:+-]+$""").matches(p)) return p
        return if (win) "\"" + p.replace("\"", "") + "\"" else com.holdfast.ssh.util.shQuote(p)
    }

    fun upload(ctx: Context, host: Host, uris: List<Uri>, progress: (String) -> Unit): List<String> {
        val c = Connector.open(host, UiPrompter)
        try {
            c.client.newSFTPClient().use { s ->
                val home = s.canonicalize(".")
                val dir = (if (home.endsWith("/")) home else "$home/") + DIR
                if (s.statExistence(dir) == null) s.mkdir(dir)
                return uris.mapIndexed { i, uri ->
                    var name = displayName(ctx, uri)
                    if (s.statExistence("$dir/$name") != null) {
                        name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "_" + name
                    }
                    progress("Uploading ${i + 1} of ${uris.size}: $name")
                    val dest = "$dir/$name"
                    ctx.contentResolver.openInputStream(uri)!!.use { inp ->
                        s.open(dest, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                            rf.RemoteFileOutputStream().use { out -> inp.copyTo(out, 64 * 1024) }
                        }
                    }
                    shellPath(dest)
                }
            }
        } finally {
            c.close()
        }
    }
}
