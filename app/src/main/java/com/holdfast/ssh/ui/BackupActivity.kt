package com.holdfast.ssh.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.holdfast.ssh.crypto.BackupCrypto
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.data.VaultData
import com.holdfast.ssh.sync.GistSync
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Encrypted backup to a file, and free sync through a private GitHub gist. */
class BackupActivity : AppCompatActivity() {
    private var pendingPass: String? = null

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = pendingPass; pendingPass = null
        if (uri == null || pass == null) return@registerForActivityResult
        thread {
            try {
                val blob = BackupCrypto.encrypt(exportJson(), pass)
                contentResolver.openOutputStream(uri)!!.use { it.write(blob) }
                runOnUiThread { toast("Backup saved") }
            } catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
        }
    }

    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val blob = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
        askPass("Backup passphrase", confirm = false) { pass -> restore(blob, pass) }
    }

    /** Sync credentials stay on this device: they are not part of the exported vault. */
    private fun exportJson(): ByteArray {
        val o = Store.data.toJson(); o.put("gistToken", ""); o.put("gistId", "")
        return o.toString().toByteArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tb = MaterialToolbar(this).apply { title = "Backup & sync" }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        fun btn(t: String, f: () -> Unit) = col.addView(Button(this).apply { text = t; setOnClickListener { f() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val f = Form(this)
        f.note("Everything (hosts, keys, snippets, port forwards, known hosts) is encrypted with your passphrase " +
            "(AES-256-GCM, PBKDF2 310k) before it leaves the phone. Lose the passphrase and the backup can't be opened.")
        col.addView(f.root)
        btn("Export backup file") { askPass("Choose a backup passphrase", confirm = true) { p ->
            pendingPass = p
            exportDoc.launch("holdfast-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".holdfast")
        } }
        btn("Import backup file") { importDoc.launch(arrayOf("*/*")) }
        val f2 = Form(this)
        f2.note("\nFree sync with a private GitHub gist: paste a GitHub token that has only the \"gist\" scope. " +
            "Push from one phone, pull on another.")
        col.addView(f2.root)
        btn("Set GitHub token") { setToken() }
        btn("Push to gist") { askPass("Sync passphrase", confirm = true) { p -> push(p) } }
        btn("Pull from gist") { askPass("Sync passphrase", confirm = false) { p -> pull(p) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tb)
            addView(android.widget.ScrollView(this@BackupActivity).apply { addView(col) })
        })
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tb.setNavigationOnClickListener { finish() }
    }

    private fun askPass(title: String, confirm: Boolean, then: (String) -> Unit) {
        val f = Form(this)
        val p1 = f.password("Passphrase")
        val p2 = if (confirm) f.password("Repeat passphrase") else null
        dialog(title).setView(f.root).setPositiveButton("OK") { _, _ ->
            val a = p1.text.toString()
            when {
                a.length < 8 -> toast("Use at least 8 characters")
                p2 != null && p2.text.toString() != a -> toast("Passphrases don't match")
                else -> then(a)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun restore(blob: ByteArray, pass: String) {
        try {
            val data = VaultData.fromJson(JSONObject(String(BackupCrypto.decrypt(blob, pass))))
            confirm("Replace everything?", "This replaces this phone's ${Store.data.hosts.size} hosts and ${Store.data.keys.size} keys " +
                "with ${data.hosts.size} hosts and ${data.keys.size} keys from the backup.", "Replace") {
                data.gistToken = Store.data.gistToken; data.gistId = Store.data.gistId
                Store.replaceAll(data); toast("Restored")
            }
        } catch (e: BackupCrypto.WrongPassphrase) { toast("Wrong passphrase") }
        catch (e: Exception) { toast("Not a valid backup: ${e.message}") }
    }

    private fun setToken() {
        val f = Form(this)
        val t = f.password("GitHub token (gist scope)", Store.data.gistToken)
        val g = f.text("Gist ID (blank = create new)", Store.data.gistId)
        dialog("GitHub sync").setView(f.root).setPositiveButton("Save") { _, _ ->
            Store.data.gistToken = t.text.toString().trim(); Store.data.gistId = g.text.toString().trim(); Store.save()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun push(pass: String) {
        val token = Store.data.gistToken
        if (token.isBlank()) { toast("Set a GitHub token first"); return }
        toast("Pushing…")
        thread {
            try {
                val id = GistSync.push(token, Store.data.gistId.ifBlank { null }, BackupCrypto.encrypt(exportJson(), pass))
                Store.data.gistId = id; Store.save()
                runOnUiThread { toast("Synced to gist $id") }
            } catch (e: Exception) { runOnUiThread { toast("Push failed: ${e.message}") } }
        }
    }

    private fun pull(pass: String) {
        val token = Store.data.gistToken
        if (token.isBlank() || Store.data.gistId.isBlank()) { toast("Set the token and gist ID first"); return }
        toast("Pulling…")
        thread {
            try {
                val blob = GistSync.pull(token, Store.data.gistId)
                runOnUiThread { restore(blob, pass) }
            } catch (e: Exception) { runOnUiThread { toast("Pull failed: ${e.message}") } }
        }
    }
}
