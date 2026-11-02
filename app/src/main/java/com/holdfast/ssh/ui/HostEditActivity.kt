package com.holdfast.ssh.ui

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.holdfast.ssh.Themes
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.util.Tailnet

class HostEditActivity : AppCompatActivity() {
    private lateinit var host: Host
    private lateinit var save: () -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = Store.host(intent.getStringExtra("host"))
        host = existing?.copy() ?: Host()
        val f = Form(this)
        val label = f.text("Label", host.label)
        val addr = f.text("Address (IP, hostname or Tailscale name)", host.address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val tsNote = f.note("")
        fun updTs() { tsNote.text = if (Tailnet.isTailscale(addr.text.toString())) "Tailscale address. Keep the Tailscale app on." else "" }
        addr.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updTs()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }); updTs()
        val port = f.number("Port", host.port)
        val user = f.text("Username", host.username)
        val keys = Store.data.keys
        val authItems = listOf("Password") + keys.map { "Key: ${it.name}" }
        val authSel = if (host.authType == "key") keys.indexOfFirst { it.id == host.keyId } + 1 else 0
        val auth = f.spinner("Login with", authItems, authSel.coerceAtLeast(0))
        val pw = f.password("Password (leave blank to be asked)", host.password ?: "")
        val group = f.text("Group", host.group)
        val tags = f.text("Tags (comma separated)", host.tags.joinToString(", "))
        val startup = f.text("Run on connect (optional)", host.startupCommand, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val others = Store.data.hosts.filter { it.id != host.id }
        val jump = f.spinner("Jump host (connect through)", listOf("None") + others.map { it.title },
            (others.indexOfFirst { it.id == host.jumpHostId } + 1).coerceAtLeast(0))
        val ka = f.number("Keep-alive seconds", host.keepAliveSec)
        val tmux = f.check("Persistent session: auto-attach tmux (Linux/macOS hosts)", host.persistentTmux)
        val log = f.check("Save session log", host.logSession)
        val themes = listOf("Default") + Themes.names()
        val theme = f.spinner("Theme", themes, themes.indexOf(host.theme).coerceAtLeast(0))
        val font = f.number("Font size (0 = default)", host.fontSize)

        val tb = MaterialToolbar(this).apply { title = if (existing == null) "New host" else "Edit host" }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tb)
            addView(f.scroll(), android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        save = {
            val a = addr.text.toString().trim()
            if (a.isEmpty() || user.text.isNullOrBlank()) toast("Address and username are required")
            else {
                host.label = label.text.toString().trim(); host.address = a
                host.port = port.text.toString().toIntOrNull() ?: 22
                host.username = user.text.toString().trim()
                val ai = auth.selectedItemPosition
                host.authType = if (ai == 0) "password" else "key"
                host.keyId = if (ai == 0) null else keys[ai - 1].id
                host.password = pw.text.toString().ifEmpty { null }
                host.group = group.text.toString().trim()
                host.tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                host.startupCommand = startup.text.toString()
                host.jumpHostId = if (jump.selectedItemPosition == 0) null else others[jump.selectedItemPosition - 1].id
                host.keepAliveSec = ka.text.toString().toIntOrNull() ?: 15
                host.persistentTmux = tmux.isChecked; host.logSession = log.isChecked
                host.theme = if (theme.selectedItemPosition == 0) "" else themes[theme.selectedItemPosition]
                host.fontSize = font.text.toString().toIntOrNull() ?: 0
                Store.upsertHost(host)
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> save()
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
