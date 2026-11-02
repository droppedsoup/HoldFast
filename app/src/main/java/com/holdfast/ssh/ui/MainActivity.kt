package com.holdfast.ssh.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.holdfast.ssh.R
import com.holdfast.ssh.data.PortForward
import com.holdfast.ssh.data.Snippet
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.databinding.ActivityMainBinding
import com.holdfast.ssh.session.SessionManager
import com.holdfast.ssh.ssh.Connector
import com.holdfast.ssh.ssh.Keys
import com.holdfast.ssh.ssh.Remote
import com.holdfast.ssh.session.UiPrompter
import com.holdfast.ssh.util.Tailnet
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: RowAdapter
    private var tab = R.id.nav_hosts
    private val storeL: () -> Unit = { runOnUiThread { render() } }
    private val sessL: () -> Unit = { runOnUiThread { renderSessionsBar() } }
    private val prompt = PromptHandler(this)
    private val promptFn: (com.holdfast.ssh.session.UiPrompter.Request) -> Unit = { prompt.handle(it) }

    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@registerForActivityResult
        importKeyDialog(text)
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        adapter = RowAdapter(::onRowClick, ::onRowMenu)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.search.doAfterTextChanged { render() }
        b.nav.setOnItemSelectedListener { tab = it.itemId; render(); true }
        b.fab.setOnClickListener { onAdd() }
        b.batteryBar.setOnClickListener { Launch.requestBattery(this) }
        b.sessionsBar.setOnClickListener { startActivity(Intent(this, TerminalActivity::class.java)) }
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(storeL); SessionManager.addListener(sessL)
        UiPrompter.attach(promptFn)
        render(); renderSessionsBar()
        showCrashIfAny()
    }

    override fun onStop() {
        Store.removeListener(storeL); SessionManager.removeListener(sessL)
        UiPrompter.detach(promptFn)
        super.onStop()
    }

    private fun showCrashIfAny() {
        val f = com.holdfast.ssh.CrashLog.pending().lastOrNull() ?: return
        val text = f.readText()
        com.holdfast.ssh.CrashLog.markShown(f)
        dialog("HoldFast crashed last time")
            .setMessage("It will also be sent to your PC (~/HoldFast-uploads) the next time you connect.\n\n" + text.take(3000))
            .setPositiveButton("Copy error") { _, _ -> copyText("HoldFast crash", text) }
            .setNegativeButton("Close", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Backup & sync")
        menu.add(0, 2, 0, "Settings")
        menu.add(0, 3, 0, "Keep sessions alive (battery)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, BackupActivity::class.java))
            2 -> startActivity(Intent(this, SettingsActivity::class.java))
            3 -> Launch.requestBattery(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // Live battery status: the exemption is what lets sessions survive the phone locking.
        val pm = getSystemService(android.os.PowerManager::class.java)
        val ok = pm.isIgnoringBatteryOptimizations(packageName)
        b.batteryBar.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun renderSessionsBar() {
        val n = SessionManager.sessions.size
        b.sessionsBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        b.sessionsBar.text = if (n == 1) "1 open session · tap to return" else "$n open sessions · tap to return"
    }

    private fun render() {
        val d = Store.data
        val q = b.search.text.toString().trim().lowercase()
        b.search.visibility = if (tab == R.id.nav_hosts || tab == R.id.nav_snippets) View.VISIBLE else View.GONE
        supportActionBar?.title = when (tab) {
            R.id.nav_keys -> "Keychain"; R.id.nav_snippets -> "Snippets"; R.id.nav_forwards -> "Port forwarding"
            else -> "HoldFast"
        }
        adapter.rows = when (tab) {
            R.id.nav_hosts -> {
                val hs = d.hosts.filter {
                    q.isEmpty() || listOf(it.title, it.address, it.username, it.group, it.tags.joinToString(" "))
                        .any { s -> s.lowercase().contains(q) }
                }
                val out = mutableListOf<Row>()
                val recent = hs.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(3)
                if (q.isEmpty() && recent.isNotEmpty() && hs.size > 4) {
                    out += Row("h:recent", "Recent", header = true)
                    recent.forEach { out += hostRow(it, "r:") }
                }
                hs.groupBy { it.group.ifBlank { "Hosts" } }.toSortedMap().forEach { (g, list) ->
                    out += Row("h:$g", g, header = true)
                    list.sortedBy { it.title.lowercase() }.forEach { out += hostRow(it, "") }
                }
                out
            }
            R.id.nav_keys -> d.keys.sortedBy { it.name }.map { Row(it.id, it.name, it.type, "${d.hosts.count { h -> h.keyId == it.id }} hosts") }
            R.id.nav_snippets -> d.snippets.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.body.lowercase().contains(q) }
                .sortedBy { it.name.lowercase() }.map { Row(it.id, it.name, it.body.lines().first()) }
            else -> d.forwards.map { f ->
                val active = SessionManager.sessions.flatMap { it.forwards }.firstOrNull { it.rule.id == f.id }
                Row(f.id, f.label.ifBlank { f.describe() }, "${Store.host(f.hostId)?.title ?: "?"} · ${f.describe()}",
                    active?.status ?: if (f.autoStart) "Auto" else null)
            }
        }
        b.empty.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
        b.empty.text = when (tab) {
            R.id.nav_keys -> "No keys yet. Tap + to generate or import one."
            R.id.nav_snippets -> "No snippets. Tap + to save a command."
            R.id.nav_forwards -> "No port forwards. Tap + to add one."
            else -> "No hosts. Tap + to add one."
        }
    }

    private fun hostRow(h: com.holdfast.ssh.data.Host, prefix: String) = Row(
        prefix + h.id, h.title, "${h.username}@${h.address}" + (if (h.port != 22) ":${h.port}" else "") +
            (if (h.authType == "key" && h.keyId != null) " · key" else ""),
        if (Tailnet.isTailscale(h.address)) "Tailscale" else null,
    )

    private fun realId(r: Row) = r.id.removePrefix("r:")

    private fun onRowClick(r: Row) {
        val id = realId(r)
        when (tab) {
            R.id.nav_hosts -> Store.host(id)?.let { Launch.connect(this, it) }
            R.id.nav_keys -> Store.key(id)?.let { k ->
                dialog(k.name).setMessage(k.publicKey)
                    .setPositiveButton("Copy public key") { _, _ -> copyText("public key", k.publicKey) }
                    .setNeutralButton("Install on host…") { _, _ -> installKeyOnHost(k.id) }
                    .setNegativeButton("Close", null).show()
            }
            R.id.nav_snippets -> editSnippet(Store.data.snippets.firstOrNull { it.id == id })
            R.id.nav_forwards -> editForward(Store.data.forwards.firstOrNull { it.id == id })
        }
    }

    private fun onRowMenu(r: Row, anchor: View) {
        val id = realId(r)
        val pm = PopupMenu(this, anchor)
        when (tab) {
            R.id.nav_hosts -> {
                val h = Store.host(id) ?: return
                listOf("Connect", "Files (SFTP)", "Edit", "Duplicate", "Set up key login", "Delete").forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        0 -> Launch.connect(this, h)
                        1 -> startActivity(Intent(this, SftpActivity::class.java).putExtra("host", h.id))
                        2 -> startActivity(Intent(this, HostEditActivity::class.java).putExtra("host", h.id))
                        3 -> Store.upsertHost(h.copy(id = com.holdfast.ssh.data.newId(), label = h.title + " copy", lastUsed = 0))
                        4 -> Launch.connect(this, h.copy(authType = "password", keyId = null, password = null))
                        5 -> confirm("Delete host?", h.title) { Store.deleteHost(h.id) }
                    }
                    true
                }
            }
            R.id.nav_keys -> {
                val k = Store.key(id) ?: return
                listOf("Copy public key", "Share public key", "Install on host…", "Export private key", "Rename", "Delete")
                    .forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        0 -> copyText("public key", k.publicKey)
                        1 -> share(k.publicKey)
                        2 -> installKeyOnHost(k.id)
                        3 -> confirm("Export private key?", "Anyone with this text can log in as you.", "Share") { share(k.privateKey) }
                        4 -> { val f = Form(this); val n = f.text("Name", k.name)
                            dialog("Rename key").setView(f.root).setPositiveButton("Save") { _, _ -> Store.upsertKey(k.copy(name = n.text.toString())) }.show() }
                        5 -> confirm("Delete key?", "Hosts using it will fall back to password login.") { Store.deleteKey(k.id) }
                    }
                    true
                }
            }
            R.id.nav_snippets -> {
                pm.menu.add(0, 0, 0, "Edit"); pm.menu.add(0, 1, 1, "Delete")
                pm.setOnMenuItemClickListener {
                    if (it.itemId == 0) editSnippet(Store.data.snippets.firstOrNull { s -> s.id == id })
                    else confirm("Delete snippet?", r.title) { Store.deleteSnippet(id) }
                    true
                }
            }
            else -> {
                val f = Store.data.forwards.firstOrNull { it.id == id } ?: return
                pm.menu.add(0, 0, 0, "Start now (opens session)"); pm.menu.add(0, 1, 1, "Edit"); pm.menu.add(0, 2, 2, "Delete")
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        0 -> Store.host(f.hostId)?.let { h ->
                            SessionManager.open(h, forward = f); startActivity(Intent(this, TerminalActivity::class.java))
                        }
                        1 -> editForward(f)
                        2 -> confirm("Delete forward?", f.describe()) { Store.deleteForward(f.id) }
                    }
                    true
                }
            }
        }
        pm.show()
    }

    private fun share(text: String) =
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))

    private fun onAdd() {
        when (tab) {
            R.id.nav_hosts -> startActivity(Intent(this, HostEditActivity::class.java))
            R.id.nav_keys -> dialog("Add key").setItems(arrayOf("Generate new key", "Paste private key", "Import key file")) { _, w ->
                when (w) { 0 -> generateKeyDialog(); 1 -> importKeyDialog(""); 2 -> importKey.launch(arrayOf("*/*")) }
            }.show()
            R.id.nav_snippets -> editSnippet(null)
            else -> editForward(null)
        }
    }

    private fun generateKeyDialog() {
        val f = Form(this)
        val name = f.text("Name", "HoldFast ${Build.MODEL}")
        val type = f.spinner("Type", Keys.TYPES, 0)
        dialog("Generate key").setView(f.root).setPositiveButton("Generate") { _, _ ->
            val t = Keys.TYPES[type.selectedItemPosition]
            toast("Generating $t key…")
            thread {
                try {
                    val k = Keys.generate(t, name.text.toString().ifBlank { "HoldFast key" })
                    Store.upsertKey(k.copy(name = name.text.toString().ifBlank { k.name }))
                    runOnUiThread { toast("Key created") }
                } catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun importKeyDialog(prefill: String) {
        val f = Form(this)
        val name = f.text("Name", "Imported key")
        val pem = f.text("Private key (PEM / OpenSSH / PuTTY)", prefill, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 6)
        val pass = f.password("Passphrase (if any)")
        val save = f.check("Remember passphrase", true)
        dialog("Import key").setView(f.scroll()).setPositiveButton("Import") { _, _ ->
            try {
                val p = pass.text.toString().ifEmpty { null }
                val k = Keys.fromPrivate(pem.text.toString(), p, name.text.toString())
                Store.upsertKey(if (save.isChecked) k else k.copy(passphrase = null))
                toast("Imported ${k.type} key")
            } catch (e: Keys.NeedsPassphrase) { toast("That key needs its passphrase.") }
            catch (e: Exception) { toast("Couldn't read key: ${e.message}") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun installKeyOnHost(keyId: String) {
        val hosts = Store.data.hosts
        if (hosts.isEmpty()) { toast("Add a host first"); return }
        dialog("Install public key on…").setItems(hosts.map { it.title }.toTypedArray()) { _, i ->
            val h = hosts[i]
            val k = Store.key(keyId) ?: return@setItems
            toast("Connecting to ${h.title}…")
            thread {
                try {
                    val c = Connector.open(h, UiPrompter)
                    val (ok, os, msg) = try { Remote.installKey(c.client, k.publicKey, h.osHint) } finally { c.close() }
                    if (ok) Store.host(h.id)?.let { it.authType = "key"; it.keyId = k.id; it.osHint = os; Store.save() }
                    runOnUiThread { dialog(if (ok) "Key installed" else "Install failed").setMessage(msg + if (ok) "\n\n${h.title} now logs in with this key." else "").setPositiveButton("OK", null).show() }
                } catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
            }
        }.show()
    }

    private fun editSnippet(s: Snippet?) {
        val f = Form(this)
        val name = f.text("Name", s?.name ?: "")
        val body = f.text("Command", s?.body ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 3)
        val enter = f.check("Press Enter after sending", s?.autoEnter ?: true)
        f.note("Use {{name}} for values you'll be asked for when running it.")
        dialog(if (s == null) "New snippet" else "Edit snippet").setView(f.scroll()).setPositiveButton("Save") { _, _ ->
            val ns = (s ?: Snippet()).copy(name = name.text.toString().ifBlank { body.text.toString().take(30) }, body = body.text.toString(), autoEnter = enter.isChecked)
            Store.upsertSnippet(ns)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun editForward(fw: PortForward?) {
        val hosts = Store.data.hosts
        if (hosts.isEmpty()) { toast("Add a host first"); return }
        val f = Form(this)
        val label = f.text("Label (optional)", fw?.label ?: "")
        val host = f.spinner("Host", hosts.map { it.title }, hosts.indexOfFirst { it.id == fw?.hostId }.coerceAtLeast(0))
        val types = listOf("Local (-L)", "Remote (-R)", "Dynamic SOCKS (-D)")
        val type = f.spinner("Type", types, listOf("L", "R", "D").indexOf(fw?.type ?: "L"))
        val bindHost = f.text("Bind address", fw?.bindHost ?: "127.0.0.1")
        val bindPort = f.number("Bind port", fw?.bindPort ?: 8080)
        val destHost = f.text("Destination host (not used for SOCKS)", fw?.destHost ?: "127.0.0.1")
        val destPort = f.number("Destination port", fw?.destPort ?: 80)
        val auto = f.check("Start automatically with the host's session", fw?.autoStart ?: true)
        dialog(if (fw == null) "New port forward" else "Edit port forward").setView(f.scroll()).setPositiveButton("Save") { _, _ ->
            Store.upsertForward((fw ?: PortForward()).copy(
                label = label.text.toString(), hostId = hosts[host.selectedItemPosition].id,
                type = listOf("L", "R", "D")[type.selectedItemPosition], bindHost = bindHost.text.toString().ifBlank { "127.0.0.1" },
                bindPort = bindPort.text.toString().toIntOrNull() ?: 8080, destHost = destHost.text.toString(),
                destPort = destPort.text.toString().toIntOrNull() ?: 80, autoStart = auto.isChecked,
            ))
        }.setNegativeButton("Cancel", null).show()
    }
}
