package com.holdfast.ssh.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.holdfast.ssh.R
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.databinding.ActivitySftpBinding
import com.holdfast.ssh.session.UiPrompter
import com.holdfast.ssh.ssh.Connected
import com.holdfast.ssh.ssh.Connector
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.Executors

class SftpActivity : AppCompatActivity() {
    private lateinit var b: ActivitySftpBinding
    private val io = Executors.newSingleThreadExecutor()
    private var conn: Connected? = null
    private var sftp: SFTPClient? = null
    private var cwd = "."
    private var entries: List<RemoteResourceInfo> = emptyList()
    private lateinit var adapter: RowAdapter
    private val prompt = PromptHandler(this)
    private val promptFn: (UiPrompter.Request) -> Unit = { prompt.handle(it) }
    private var pendingDownload: RemoteResourceInfo? = null

    private val pickUpload = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { upload(it) }
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        uris.forEach { upload(it) }
    }
    private val createDownload = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val e = pendingDownload; pendingDownload = null
        if (uri != null && e != null) download(e, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySftpBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val host = Store.host(intent.getStringExtra("host")) ?: run { finish(); return }
        supportActionBar?.title = "Files · ${host.title}"
        adapter = RowAdapter(::onClick, ::onMenu)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener { list(cwd) }
        b.fab.setOnClickListener {
            dialog("Upload here").setItems(arrayOf("Photos & videos", "Files")) { _, w ->
                if (w == 0) pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                else pickUpload.launch(arrayOf("*/*"))
            }.show()
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("transfers", "File transfers", NotificationManager.IMPORTANCE_LOW))
        UiPrompter.attach(promptFn)
        busy(true)
        io.execute {
            try {
                val c = Connector.open(host, UiPrompter)
                conn = c
                sftp = c.client.newSFTPClient()
                cwd = sftp!!.canonicalize(".")
                list(cwd)
            } catch (e: Exception) {
                runOnUiThread { busy(false); dialog("Couldn't open files").setMessage(e.message).setPositiveButton("OK") { _, _ -> finish() }.show() }
            }
        }
    }

    override fun onDestroy() {
        UiPrompter.detach(promptFn)
        io.execute { try { sftp?.close() } catch (_: Exception) {}; conn?.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun busy(on: Boolean) { b.swipe.isRefreshing = on }

    private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
    private fun parent(p: String): String {
        val t = p.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    private fun list(path: String) {
        runOnUiThread { busy(true) }
        io.execute {
            try {
                val s = sftp ?: return@execute
                val items = s.ls(path).filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareBy<RemoteResourceInfo>({ !it.isDirectory }, { it.name.lowercase() }))
                cwd = path
                entries = items
                runOnUiThread {
                    busy(false)
                    b.path.text = path
                    val rows = mutableListOf<Row>()
                    if (path != "/") rows += Row("..", "⬑  ..", "Up one folder")
                    items.forEach { e ->
                        rows += Row(e.path, (if (e.isDirectory) "📁  " else "📄  ") + e.name,
                            if (e.isDirectory) "" else human(e.attributes.size))
                    }
                    adapter.rows = rows
                }
            } catch (e: Exception) {
                runOnUiThread { busy(false); toast("Can't open $path: ${e.message}") }
            }
        }
    }

    private fun human(n: Long): String = when {
        n < 1024 -> "$n B"; n < 1 shl 20 -> "%.1f KB".format(n / 1024.0)
        n < 1 shl 30 -> "%.1f MB".format(n / 1048576.0); else -> "%.2f GB".format(n / 1073741824.0)
    }

    private fun entry(r: Row) = entries.firstOrNull { it.path == r.id }

    private fun onClick(r: Row) {
        if (r.id == "..") { list(parent(cwd)); return }
        val e = entry(r) ?: return
        if (e.isDirectory || e.attributes.type == FileMode.Type.SYMLINK) list(e.path) else onMenu(r, b.list)
    }

    private fun onMenu(r: Row, anchor: View) {
        if (r.id == "..") return
        val e = entry(r) ?: return
        val pm = PopupMenu(this, anchor)
        val opts = if (e.isDirectory) listOf("Open", "Rename", "Permissions", "Delete")
        else listOf("Download", "View / edit", "Rename", "Permissions", "Delete")
        opts.forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
        pm.setOnMenuItemClickListener {
            when (opts[it.itemId]) {
                "Open" -> list(e.path)
                "Download" -> { pendingDownload = e; createDownload.launch(e.name) }
                "View / edit" -> edit(e)
                "Rename" -> rename(e)
                "Permissions" -> chmod(e)
                "Delete" -> confirm("Delete ${e.name}?", if (e.isDirectory) "The folder must be empty." else "This can't be undone.") {
                    op { if (e.isDirectory) it.rmdir(e.path) else it.rm(e.path) }
                }
            }
            true
        }
        pm.show()
    }

    private fun op(block: (SFTPClient) -> Unit) {
        io.execute {
            try { sftp?.let(block); list(cwd) } catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
        }
    }

    private fun rename(e: RemoteResourceInfo) {
        val f = Form(this); val n = f.text("New name", e.name)
        dialog("Rename").setView(f.root).setPositiveButton("Rename") { _, _ ->
            op { it.rename(e.path, join(cwd, n.text.toString())) }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chmod(e: RemoteResourceInfo) {
        val f = Form(this)
        val mode = f.text("Mode (octal)", Integer.toOctalString(e.attributes.mode.permissionsMask), InputType.TYPE_CLASS_NUMBER)
        f.note("Windows servers mostly ignore this.")
        dialog("Permissions").setView(f.root).setPositiveButton("Apply") { _, _ ->
            val m = mode.text.toString().toIntOrNull(8) ?: return@setPositiveButton
            op { it.chmod(e.path, m) }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun edit(e: RemoteResourceInfo) {
        if (e.attributes.size > 1_000_000) { toast("Too big to edit here (over 1 MB). Download it instead."); return }
        busy(true)
        io.execute {
            try {
                val text = sftp!!.open(e.path).use { rf -> rf.RemoteFileInputStream().use { it.readBytes() } }.toString(Charsets.UTF_8)
                runOnUiThread {
                    busy(false)
                    val f = Form(this)
                    val et = f.text(e.name, text, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 12)
                    et.typeface = android.graphics.Typeface.MONOSPACE
                    dialog("Edit").setView(f.scroll()).setPositiveButton("Save") { _, _ ->
                        val data = et.text.toString().toByteArray()
                        op { s ->
                            s.open(e.path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                                rf.RemoteFileOutputStream().use { it.write(data) }
                            }
                        }
                    }.setNegativeButton("Close", null).show()
                }
            } catch (ex: Exception) { runOnUiThread { busy(false); toast("Can't read: ${ex.message}") } }
        }
    }

    private fun displayName(uri: Uri): String = com.holdfast.ssh.ssh.Uploader.displayName(this, uri)

    private fun upload(uri: Uri) {
        val name = displayName(uri)
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
        val dir = cwd
        io.execute {
            val exists = try { sftp?.statExistence(join(dir, name)) != null } catch (_: Exception) { false }
            runOnUiThread {
                if (!exists) doUpload(uri, dir, name, size, overwrite = false)
                else dialog("$name already exists").setMessage("Replace it, or keep both?")
                    .setPositiveButton("Keep both") { _, _ -> doUpload(uri, dir, "${System.currentTimeMillis() / 1000}_$name", size, false) }
                    .setNeutralButton("Replace") { _, _ -> doUpload(uri, dir, name, size, overwrite = true) }
                    .setNegativeButton("Skip", null).show()
            }
        }
    }

    /** Uploads to "<name>.part" then renames, so a failed transfer never leaves a truncated file in place. */
    private fun doUpload(uri: Uri, dir: String, name: String, size: Long, overwrite: Boolean) {
        val dest = join(dir, name)
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val part = join(dir, ".$name.holdfast-$tag.part")
        transfer("Uploading $name", size) { progress ->
            val s = sftp!!
            var created = false // only ever clean up a temp file this upload created
            try {
                contentResolver.openInputStream(uri)!!.use { inp ->
                    // EXCL: never clobber an existing file, even one that happens to share the temp name.
                    s.open(part, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { rf ->
                        created = true
                        rf.RemoteFileOutputStream().use { out -> copy(inp, out, progress) }
                    }
                }
                if (overwrite && s.statExistence(dest) != null) {
                    // Keep the original until the new file is in place; roll back if the swap fails.
                    val bak = join(dir, ".$name.holdfast-$tag.bak")
                    s.rename(dest, bak)
                    try { s.rename(part, dest) } catch (e: Exception) { s.rename(bak, dest); throw e }
                    try { s.rm(bak) } catch (_: Exception) {}
                } else {
                    s.rename(part, dest)
                }
            } catch (e: Exception) {
                if (created) try { if (s.statExistence(part) != null) s.rm(part) } catch (_: Exception) {}
                throw e
            }
        }
    }

    private fun download(e: RemoteResourceInfo, uri: Uri) {
        transfer("Downloading ${e.name}", e.attributes.size) { progress ->
            sftp!!.open(e.path).use { rf ->
                rf.RemoteFileInputStream().use { inp ->
                    contentResolver.openOutputStream(uri)!!.use { out -> copy(inp, out, progress) }
                }
            }
        }
    }

    private fun copy(i: InputStream, o: OutputStream, progress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); total += n; progress(total) }
    }

    private fun transfer(title: String, size: Long, work: ((Long) -> Unit) -> Unit) {
        val nm = getSystemService(NotificationManager::class.java)
        val id = (System.nanoTime() % 100000).toInt() + 10
        val nb = NotificationCompat.Builder(this, "transfers").setSmallIcon(R.drawable.ic_stat_holdfast)
            .setContentTitle(title).setOnlyAlertOnce(true).setOngoing(true)
        runOnUiThread { b.progress.visibility = View.VISIBLE; b.progress.isIndeterminate = size <= 0 }
        io.execute {
            var last = 0L
            try {
                work { done ->
                    val now = System.currentTimeMillis()
                    if (now - last > 300) {
                        last = now
                        val pct = if (size > 0) (done * 100 / size).toInt() else 0
                        try { nm.notify(id, nb.setProgress(100, pct, size <= 0).setContentText(human(done)).build()) } catch (_: SecurityException) {}
                        runOnUiThread { b.progress.progress = pct }
                    }
                }
                try { nm.notify(id, nb.setOngoing(false).setProgress(0, 0, false).setContentText("Done").build()) } catch (_: SecurityException) {}
                runOnUiThread { b.progress.visibility = View.GONE; toast("$title: done") }
                list(cwd)
            } catch (e: Exception) {
                try { nm.notify(id, nb.setOngoing(false).setProgress(0, 0, false).setContentText("Failed: ${e.message}").build()) } catch (_: SecurityException) {}
                runOnUiThread { b.progress.visibility = View.GONE; toast("Failed: ${e.message}") }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "New folder"); menu.add(0, 2, 1, "Go to path…"); menu.add(0, 3, 2, "Home")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> { val f = Form(this); val n = f.text("Folder name")
                dialog("New folder").setView(f.root).setPositiveButton("Create") { _, _ -> op { it.mkdir(join(cwd, n.text.toString())) } }.show() }
            2 -> { val f = Form(this); val p = f.text("Path", cwd)
                dialog("Go to").setView(f.root).setPositiveButton("Go") { _, _ -> list(p.text.toString()) }.show() }
            3 -> io.execute { try { list(sftp!!.canonicalize(".")) } catch (_: Exception) {} }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cwd != "/" && cwd.isNotEmpty() && b.path.text.isNotEmpty() && cwd.count { it == '/' } > 1) list(parent(cwd))
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
