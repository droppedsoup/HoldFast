package com.holdfast.ssh.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.holdfast.ssh.HoldFastApp
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.session.SessionManager
import com.holdfast.ssh.session.UiPrompter
import com.holdfast.ssh.ssh.Uploader
import kotlin.concurrent.thread

/** "Share → HoldFast" from Gallery or any app: uploads to the PC and drops the path into the open terminal. */
class ShareActivity : AppCompatActivity() {
    private val prompt = PromptHandler(this)
    private val promptFn: (UiPrompter.Request) -> Unit = { prompt.handle(it) }

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) proceed() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrompter.attach(promptFn)
        if (HoldFastApp.isUnlocked()) proceed() else unlock.launch(Intent(this, LockActivity::class.java))
    }

    private fun proceed() {
        // Only content:// URIs the sender granted us. Never file:// (could point at HoldFast's private files).
        val uris = extractUris(intent).filter { it.scheme == "content" && it.authority?.startsWith(packageName) != true }
        if (uris.isEmpty()) { toast("Nothing to upload"); finish(); return }
        val session = SessionManager.current()
        val hosts = Store.data.hosts
        when {
            session != null -> start(session.host, uris, pasteInto = true)
            hosts.size == 1 -> start(hosts[0], uris, pasteInto = false)
            hosts.isEmpty() -> { toast("Add a host in HoldFast first"); finish() }
            else -> dialog("Upload ${uris.size} item(s) to…")
                .setItems(hosts.map { it.title }.toTypedArray()) { _, i -> start(hosts[i], uris, false) }
                .setOnCancelListener { finish() }.show()
        }
    }

    override fun onDestroy() { UiPrompter.detach(promptFn); super.onDestroy() }

    private fun extractUris(i: Intent): List<Uri> {
        @Suppress("DEPRECATION")
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
    }

    private fun start(host: Host, uris: List<Uri>, pasteInto: Boolean) {
        dialog("Upload to ${host.title}?")
            .setMessage("${uris.size} item(s) to ~/${Uploader.DIR}" + if (pasteInto) ". The path will be pasted into your open terminal." else "")
            .setPositiveButton("Upload") { _, _ -> doUpload(host, uris, pasteInto) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun doUpload(host: Host, uris: List<Uri>, pasteInto: Boolean) {
        val dlg: AlertDialog = dialog("Uploading to ${host.title}").setMessage("Connecting…").setCancelable(false).show()
        thread {
            try {
                val paths = Uploader.upload(this, host, uris) { msg -> runOnUiThread { dlg.setMessage(msg) } }
                val text = paths.joinToString(" ")
                runOnUiThread {
                    dlg.dismiss()
                    val s = SessionManager.current()
                    if (pasteInto && s != null) {
                        s.terminal.emulator?.paste("$text ")
                        startActivity(Intent(this, TerminalActivity::class.java))
                        toast("Uploaded. Path is in your terminal.")
                        finish()
                    } else {
                        dialog("Uploaded ${paths.size} item(s)").setMessage(paths.joinToString("\n"))
                            .setPositiveButton("Copy path") { _, _ -> copyText("path", text); finish() }
                            .setNegativeButton("Done") { _, _ -> finish() }
                            .setOnCancelListener { finish() }.show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    dialog("Upload failed").setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                }
            }
        }
    }
}
