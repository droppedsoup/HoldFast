package com.holdfast.ssh.ui

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.holdfast.ssh.ssh.Uploader
import kotlin.concurrent.thread
import com.google.android.material.tabs.TabLayout
import com.holdfast.ssh.Prefs
import com.holdfast.ssh.Themes
import com.holdfast.ssh.data.Store
import com.holdfast.ssh.databinding.ActivityTerminalBinding
import com.holdfast.ssh.session.ActiveSession
import com.holdfast.ssh.session.SessionManager
import com.holdfast.ssh.session.UiPrompter
import com.holdfast.ssh.util.MenuTap
import com.holdfast.ssh.util.SnippetVars
import com.holdfast.ssh.util.TapText
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

class TerminalActivity : AppCompatActivity(), ActiveSession.Listener {
    private lateinit var b: ActivityTerminalBinding
    private var current: ActiveSession? = null
    private var ctrlSticky = false
    private var altSticky = false
    private var volCtrl = false
    private var fnPage = false
    private val prompt = PromptHandler(this)
    private val promptFn: (UiPrompter.Request) -> Unit = { prompt.handle(it) }
    private val sessL: () -> Unit = { runOnUiThread { renderTabs() } }
    private var suppressTabEvent = false

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uploadUris(it) }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uploadUris(it) }

    private fun chooseUpload() {
        if (current == null) return
        dialog("Upload to ${current!!.title}").setItems(arrayOf("Photos & videos", "Files")) { _, w ->
            if (w == 0) pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            else pickFiles.launch(arrayOf("*/*"))
        }.show()
    }

    /** Uploads to ~/HoldFast-uploads and pastes the path(s) at the prompt (no Enter), e.g. for Claude. */
    private fun uploadUris(uris: List<Uri>) {
        val s = current ?: return
        if (uris.isEmpty()) return
        supportActionBar?.subtitle = "Uploading…"
        thread {
            try {
                val paths = Uploader.upload(this, s.host, uris) { msg -> runOnUiThread { supportActionBar?.subtitle = msg } }
                runOnUiThread {
                    supportActionBar?.subtitle = null
                    s.terminal.emulator?.paste(paths.joinToString(" ") + " ")
                    toast("Uploaded ${paths.size} to ~/${Uploader.DIR}")
                }
            } catch (e: Exception) {
                runOnUiThread { supportActionBar?.subtitle = null; toast("Upload failed: ${e.message}") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.terminal.setTerminalViewClient(viewClient)
        // Order matters: setTextSize() creates the renderer; setTypeface() before it is a NullPointerException.
        applyFont(Prefs.fontSize)
        b.terminal.setTypeface(Typeface.MONOSPACE)
        b.terminal.keepScreenOn = Prefs.keepScreenOn
        b.terminal.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> b.terminal.post { syncSize() } }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                if (!suppressTabEvent) SessionManager.get(t.tag as String)?.let { show(it) }
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        b.retry.setOnClickListener { current?.kick() }
        b.openTailscale.setOnClickListener {
            packageManager.getLaunchIntentForPackage("com.tailscale.ipn")?.let { startActivity(it) }
                ?: toast("Tailscale app not found")
        }
        b.ime.term = b.terminal
        b.ime.ctrlOrAltSticky = { ctrlSticky || altSticky || volCtrl }
        setupSelectLayer()
        buildKeys()
        b.composeSend.setOnClickListener { sendCompose() }
        b.compose.setHorizontallyScrolling(false)
        b.compose.maxLines = 4
        b.compose.setOnEditorActionListener { _, _, _ -> sendCompose(); true }
    }

    // ---- long-press highlight: show the screen + scroll-back as a native selectable TextView ----

    private fun setupSelectLayer() {
        b.selectText.typeface = Typeface.MONOSPACE
        b.selectText.highlightColor = 0x664A90E2
        b.selectText.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(m: android.view.ActionMode, menu: Menu): Boolean {
                menu.add(0, 0x7f01, 0, "Paste to prompt")
                return true
            }
            override fun onPrepareActionMode(m: android.view.ActionMode, menu: Menu) = false
            override fun onActionItemClicked(m: android.view.ActionMode, item: MenuItem): Boolean {
                if (item.itemId != 0x7f01) return false
                val t = b.selectText
                val sel = t.text.subSequence(t.selectionStart.coerceAtLeast(0), t.selectionEnd.coerceAtLeast(0)).toString()
                m.finish()
                if (sel.isNotEmpty()) current?.terminal?.emulator?.paste(sel)
                return true
            }
            override fun onDestroyActionMode(m: android.view.ActionMode) { b.selectText.post { endSelect(force = true) } }
        }
    }

    private fun startSelect(e: MotionEvent) {
        val s = current ?: return
        val em = s.terminal.emulator ?: return
        val scr = em.screen
        val hist = scr.activeTranscriptRows
        val text = (-hist until em.mRows).joinToString("\n") { r ->
            (scr.getSelectedText(0, r, em.mColumns, r, false) ?: "").trimEnd()
        }
        val r = b.terminal.mRenderer
        val lineH = r.fontLineSpacing
        val t = b.selectText
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, currentFontPx().toFloat())
        if (android.os.Build.VERSION.SDK_INT >= 28) t.lineHeight = lineH
        val colors = em.mColors.mCurrentColors
        t.setTextColor(colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] or 0xff000000.toInt())
        b.selectLayer.setBackgroundColor(colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] or 0xff000000.toInt())
        t.text = text
        b.ime.resetIme()
        b.selectLayer.visibility = View.VISIBLE
        val x = e.x; val y = e.y
        b.selectLayer.post {
            b.selectLayer.scrollTo(0, ((b.terminal.topRow + hist) * lineH).coerceAtLeast(0))
            val off = t.getOffsetForPosition(x, y + b.selectLayer.scrollY)
            val (st, en) = com.holdfast.ssh.util.ImeDiff.wordAt(t.text.toString(), off)
            val sp = t.text as? android.text.Spannable ?: return@post
            android.text.Selection.setSelection(sp, st)
            // Let TextView's own long-press logic pick the word + start handles/toolbar; fall back to a plain range.
            if (!t.performLongClick() || !t.hasSelection()) android.text.Selection.setSelection(sp, st, en)
            buzz()
        }
    }

    private fun endSelect(force: Boolean = false) {
        if (b.selectLayer.visibility != View.VISIBLE || (!force && b.selectText.hasSelection())) return
        b.selectLayer.visibility = View.GONE
        b.selectText.text = ""
        focusInput(false)
    }

    /** Keyboard focus goes to the autocorrect proxy, or straight to the terminal when autocorrect is off. */
    private fun focusInput(showKeyboard: Boolean) {
        val ac = Prefs.imeAutocorrect
        b.terminal.isFocusable = !ac
        b.terminal.isFocusableInTouchMode = !ac
        val v: View = if (ac) b.ime else b.terminal
        v.requestFocus()
        if (showKeyboard) getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }

    private var fontPx = 0
    private fun currentFontPx() = fontPx

    // ---- compose box: normal Android keyboard with autocorrect / voice, Send = text + Enter ----

    private fun toggleCompose() {
        val show = b.composeBar.visibility != View.VISIBLE
        b.composeBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) { b.compose.requestFocus(); imm.showSoftInput(b.compose, 0) }
        else focusInput(true)
    }

    private fun sendCompose() {
        val s = current ?: return
        val text = b.compose.text.toString()
        if (text.contains("\n")) s.terminal.emulator?.paste(text) else if (text.isNotEmpty()) s.write(text)
        s.write("\r")
        b.compose.setText("")
        toBottom()
    }

    private fun toBottom() { if (b.terminal.topRow != 0) { b.terminal.topRow = 0; b.terminal.invalidate() } }

    /** Termux's onScreenUpdated() always snaps to the bottom; keep the user's scroll-back position instead. */
    private fun refreshKeepingScroll() {
        val t = b.terminal
        val em = t.mEmulator
        val top = t.topRow
        if (top == 0 || em == null) { t.onScreenUpdated(); return }
        val shift = em.scrollCounter
        t.onScreenUpdated()
        t.topRow = maxOf(top - shift, -em.screen.activeTranscriptRows)
        t.invalidate()
    }

    override fun onStart() {
        super.onStart()
        SessionManager.addListener(sessL)
        UiPrompter.attach(promptFn)
        if (SessionManager.sessions.isEmpty()) { finish(); return }
        SessionManager.sessions.forEach { it.listener = this }
        val target = intent?.getStringExtra("session")?.let { SessionManager.get(it) }
        intent?.removeExtra("session")
        (target ?: SessionManager.current())?.let { current = it; SessionManager.currentId = it.id }
        renderTabs()
        current?.let { show(it) }
    }

    override fun onStop() {
        SessionManager.removeListener(sessL)
        UiPrompter.detach(promptFn)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("session")?.let { id -> SessionManager.get(id)?.let { show(it) } }
    }

    override fun onPause() {
        com.holdfast.ssh.session.DoneNotifier.visibleSessionId = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        com.holdfast.ssh.session.DoneNotifier.visibleSessionId = current?.id
        if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- tabs / sessions ----

    private fun renderTabs() {
        val ss = SessionManager.sessions
        if (ss.isEmpty()) { finish(); return }
        ss.forEach { it.listener = this }
        suppressTabEvent = true
        b.tabs.removeAllTabs()
        ss.forEach { s ->
            val dot = when (s.state) {
                ActiveSession.State.CONNECTED -> "● "
                ActiveSession.State.CONNECTING, ActiveSession.State.RECONNECTING -> "◌ "
                else -> "○ "
            }
            b.tabs.addTab(b.tabs.newTab().setCustomView(tabView(s, dot + s.title)).setTag(s.id), s.id == current?.id)
        }
        b.tabs.visibility = View.VISIBLE
        suppressTabEvent = false
        if (current == null || ss.none { it.id == current?.id }) ss.lastOrNull()?.let { show(it) }
        renderBanner()
    }

    private fun tabView(s: ActiveSession, text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(android.widget.TextView(context).apply {
            this.text = text; setTextColor(0xFFD8E2EA.toInt()); textSize = 13f; maxLines = 1
        })
        addView(android.widget.TextView(context).apply {
            this.text = "✕"; setTextColor(0xFF9FB0BD.toInt()); textSize = 15f
            setPadding(dp(12), dp(6), dp(4), dp(6))
            contentDescription = "Close ${s.title}"
            setOnClickListener { closeTab(s) }
        })
    }

    private fun closeTab(s: ActiveSession) =
        confirm("Close ${s.title}?", "This ends the remote shell.", "Close") { SessionManager.close(s); renderTabs() }

    private fun show(s: ActiveSession) {
        current = s
        SessionManager.currentId = s.id
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            com.holdfast.ssh.session.DoneNotifier.visibleSessionId = s.id
        com.holdfast.ssh.session.DoneNotifier.clear(s)
        b.ime.resetIme()
        b.terminal.attachSession(s.terminal)
        Themes.apply(s.terminal.emulator, s.host.theme.ifBlank { Prefs.theme })
        b.root.setBackgroundColor(Themes.background(s.host.theme.ifBlank { Prefs.theme }))
        applyFont(if (s.host.fontSize > 0) s.host.fontSize else Prefs.fontSize)
        supportActionBar?.title = s.title
        renderBanner()
        b.terminal.post { syncSize(); b.terminal.onScreenUpdated() }
        focusInput(false)
    }

    private fun syncSize() {
        val s = current ?: return
        val em = s.terminal.emulator ?: return
        s.resize(em.mColumns, em.mRows)
    }

    private fun applyFont(sp: Int) {
        fontPx = (sp * resources.displayMetrics.scaledDensity).toInt()
        b.terminal.setTextSize(fontPx)
    }

    private fun renderBanner() {
        val s = current ?: return
        val st = s.state
        val show = st != ActiveSession.State.CONNECTED
        b.banner.visibility = if (show) View.VISIBLE else View.GONE
        b.bannerText.text = s.status
        b.retry.text = if (st == ActiveSession.State.RECONNECTING) "Retry now" else "Reconnect"
        b.retry.visibility = if (st == ActiveSession.State.CONNECTING) View.GONE else View.VISIBLE
        b.openTailscale.visibility = if (s.tailscaleHint) View.VISIBLE else View.GONE
        if (s.tailscaleHint) b.bannerText.text = s.status + "\nTailscale looks off. Turn it on, then tap Reconnect."
    }

    // ---- ActiveSession.Listener ----

    override fun onScreenUpdated(s: ActiveSession) { if (s.id == current?.id) refreshKeepingScroll() }
    override fun onStateChanged(s: ActiveSession) { renderTabs() }
    override fun onBell(s: ActiveSession) {
        if (Prefs.vibrateBell) getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, 80))
    }
    override fun onCopy(s: ActiveSession, text: String) { copyText("terminal", text) }
    override fun onPasteRequest(s: ActiveSession) { paste() }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        current?.terminal?.emulator?.paste(text)
    }

    // ---- menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        listOf("New tab", "Snippets", "Paste", "Files (SFTP)", "Open link on screen", "Reconnect now",
            "Bigger text", "Smaller text", "Theme", "Close tab", "Upload photo / file").forEachIndexed { i, t ->
            menu.add(0, i + 1, i, t).apply { if (i == 1) setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val s = current
        when (item.itemId) {
            android.R.id.home -> { startActivity(Intent(this, MainActivity::class.java)); return true }
            1 -> newTab()
            2 -> snippets()
            3 -> paste()
            4 -> s?.let { startActivity(Intent(this, SftpActivity::class.java).putExtra("host", it.host.id)) }
            5 -> openLinks()
            6 -> s?.let { if (it.state == ActiveSession.State.CONNECTED) it.forceReconnect() else it.kick() }
            7 -> bumpFont(1)
            8 -> bumpFont(-1)
            9 -> pickTheme()
            11 -> chooseUpload()
            10 -> s?.let { closeTab(it) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun newTab() {
        val hosts = Store.data.hosts
        dialog("Open new tab").setItems(hosts.map { it.title }.toTypedArray()) { _, i ->
            Launch.connect(this, hosts[i])
        }.show()
    }

    private fun bumpFont(d: Int) { Prefs.fontSize = Prefs.fontSize + d; applyFont(Prefs.fontSize) }

    private fun pickTheme() {
        val names = Themes.names()
        dialog("Theme").setItems(names.toTypedArray()) { _, i ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit().putString("theme", names[i]).apply()
            current?.let { show(it) }
        }.show()
    }

    private fun openLinks() {
        val text = current?.terminal?.emulator?.screen?.transcriptText ?: return
        val urls = Regex("""https?://[^\s"'<>]+""").findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList().takeLast(20).reversed()
        if (urls.isEmpty()) { toast("No links on screen"); return }
        dialog("Open link").setItems(urls.toTypedArray()) { _, i ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urls[i])))
        }.show()
    }

    private fun snippets() {
        val list = Store.data.snippets.sortedBy { it.name.lowercase() }
        if (list.isEmpty()) { toast("No snippets yet. Add them on the Snippets tab."); return }
        val f = Form(this)
        val all = if (SessionManager.sessions.size > 1) f.check("Send to all open tabs", false) else null
        val lv = android.widget.ListView(this).apply {
            adapter = object : android.widget.ArrayAdapter<String>(this@TerminalActivity, android.R.layout.simple_list_item_2, android.R.id.text1, list.map { it.name }) {
                override fun getView(p: Int, c: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(p, c, parent)
                    v.findViewById<android.widget.TextView>(android.R.id.text2).text = list[p].body.lines().first()
                    return v
                }
            }
        }
        f.root.addView(lv, LinearLayout.LayoutParams(-1, dp(360)))
        val dlg = dialog("Run snippet").setView(f.root).setNegativeButton("Cancel", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> dlg.dismiss(); runSnippet(list[i], all?.isChecked == true) }
        dlg.show()
    }

    private fun runSnippet(sn: com.holdfast.ssh.data.Snippet, broadcast: Boolean) {
        val vars = SnippetVars.names(sn.body)
        val send = { body: String ->
            val text = body + if (sn.autoEnter) "\r" else ""
            val targets = if (broadcast) SessionManager.sessions.toList() else listOfNotNull(current)
            targets.forEach { it.write(text) }
        }
        if (vars.isEmpty()) { send(sn.body); return }
        val f = Form(this)
        val fields = vars.associateWith { f.text(it) }
        dialog(sn.name).setView(f.scroll()).setPositiveButton("Run") { _, _ ->
            send(SnippetVars.fill(sn.body, fields.mapValues { it.value.text.toString() }))
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- extra keys bar ----

    private fun buildKeys() {
        b.keys.removeAllViews()
        val tokens = (if (fnPage) Prefs.FN_KEYS else Prefs.extraKeys).split(',').map { it.trim() }.filter { it.isNotEmpty() } +
            listOf(if (fnPage) "ABC" else "Fn", "✎", "PASTE", "⌘", "📎")
        tokens.forEach { t ->
            val btn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label(t); isAllCaps = false; minWidth = dp(44); minimumWidth = dp(44)
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(0xFFD8E2EA.toInt())
                setOnClickListener { press(t, this) }
            }
            b.keys.addView(btn, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun label(t: String) = when (t) {
        "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"; "PGUP" -> "PgUp"; "PGDN" -> "PgDn"
        "HOME" -> "Home"; "END" -> "End"; "ESC" -> "Esc"; "TAB" -> "Tab"; "STAB" -> "⇧Tab"; "CTRL" -> "Ctrl"; "ALT" -> "Alt"
        "PASTE" -> "Paste"; else -> t
    }

    private fun press(t: String, btn: Button) {
        if (t != "CTRL" && t != "ALT" && t != "Fn" && t != "ABC") b.ime.resetIme()
        if (t != "UP" && t != "DOWN" && t != "PGUP" && t != "PGDN") toBottom()
        val code = when (t) {
            "ESC" -> KeyEvent.KEYCODE_ESCAPE; "TAB" -> KeyEvent.KEYCODE_TAB
            "UP" -> KeyEvent.KEYCODE_DPAD_UP; "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT; "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "HOME" -> KeyEvent.KEYCODE_MOVE_HOME; "END" -> KeyEvent.KEYCODE_MOVE_END
            "PGUP" -> KeyEvent.KEYCODE_PAGE_UP; "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "DEL" -> KeyEvent.KEYCODE_FORWARD_DEL; "ENTER" -> KeyEvent.KEYCODE_ENTER
            else -> if (t.matches(Regex("F([1-9]|1[0-2])"))) KeyEvent.KEYCODE_F1 + t.drop(1).toInt() - 1 else -1
        }
        when {
            t == "CTRL" -> { ctrlSticky = !ctrlSticky; btn.alpha = if (ctrlSticky) 0.5f else 1f }
            t == "ALT" -> { altSticky = !altSticky; btn.alpha = if (altSticky) 0.5f else 1f }
            t == "Fn" || t == "ABC" -> { fnPage = !fnPage; buildKeys() }
            t == "STAB" -> { current?.write("\u001b[Z"); clearSticky() }
            t == "PASTE" -> paste()
            t == "⌘" -> snippets()
            t == "📎" -> chooseUpload()
            t == "✎" -> toggleCompose()
            code >= 0 -> {
                var mod = 0
                if (ctrlSticky) mod = mod or KeyHandler.KEYMOD_CTRL
                if (altSticky) mod = mod or KeyHandler.KEYMOD_ALT
                b.terminal.handleKeyCode(code, mod)
                clearSticky()
            }
            else -> {
                t.codePoints().forEach { cp -> b.terminal.inputCodePoint(cp, ctrlSticky, altSticky) }
                clearSticky()
            }
        }
    }

    private fun clearSticky() {
        if (!ctrlSticky && !altSticky) return
        ctrlSticky = false; altSticky = false
        for (i in 0 until b.keys.childCount) b.keys.getChildAt(i).alpha = 1f
    }

    // ---- swipe left/right on the terminal to switch tabs ----

    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x; val dy = e2.y - start.y
                if (kotlin.math.abs(dx) < dp(100) || kotlin.math.abs(dx) < 2.5f * kotlin.math.abs(dy)) return false
                if (kotlin.math.abs(vx) < 600) return false
                switchTab(if (dx < 0) 1 else -1)
                return true
            }
        })
    }

    private fun switchTab(dir: Int) {
        val ss = SessionManager.sessions
        if (ss.size < 2) return
        val i = ss.indexOfFirst { it.id == current?.id }
        val next = ss[((i + dir) % ss.size + ss.size) % ss.size]
        show(next)
        renderTabs()
        b.terminal.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val r = android.graphics.Rect()
        if (b.selectLayer.visibility != View.VISIBLE && b.terminal.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && b.selectLayer.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) endSelect(force = true)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Prefs.volDownCtrl) {
            volCtrl = event.action == KeyEvent.ACTION_DOWN
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- tap actions: pick menu option, open link, paste highlighted code ----

    private fun handleTap(e: MotionEvent): Boolean {
        val s = current ?: return false
        val em = s.terminal.emulator ?: return false
        val scr = em.screen
        val (col, row) = b.terminal.getColumnAndRow(e, true).let { it[0] to it[1] }
        val top = b.terminal.topRow
        val rows = (top until top + em.mRows).toList()
        val line = { r: Int -> scr.getSelectedText(0, r, em.mColumns, r, false) ?: "" }

        if (top == 0) MenuTap.arrows(rows.map(line), row - top)?.let { n ->
            val key = if (n < 0) "\u001b[A" else "\u001b[B"
            s.write(key.repeat(kotlin.math.abs(n)) + "\r")
            buzz(); return true
        }
        val text = line(row)
        TapText.urlAt(text, col, em.mColumns, if (row + 1 < em.mRows) line(row + 1) else null)?.let { u ->
            buzz(); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); return true
        }
        val tr = scr.allocateFullLineIfNecessary(scr.externalToInternalRow(row))
        val chars = StringBuilder(); val fg = IntArray(em.mColumns)
        for (c in 0 until em.mColumns) {
            chars.append(tr.mText.getOrElse(tr.findStartOfColumn(c)) { ' ' })
            val st = tr.getStyle(c)
            val f = com.termux.terminal.TextStyle.decodeForeColor(st)
            val argb = if (f and 0xff000000.toInt() == 0xff000000.toInt()) f else em.mColors.mCurrentColors.getOrElse(f) { 0 }
            val dim = com.termux.terminal.TextStyle.decodeEffect(st) and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_DIM != 0
            // Only saturated colours count as "code"; greys / dim hint text (e.g. Claude's input placeholder) stay plain.
            fg[c] = if (dim || f == 256 || !colourful(argb)) 256 else f
        }
        TapText.codeAt(chars.toString(), fg, col)?.let { code ->
            if (b.composeBar.visibility == View.VISIBLE) b.compose.text.insert(b.compose.selectionStart.coerceAtLeast(0), code)
            else em.paste(code)
            buzz(); toast("Pasted: $code"); return true
        }
        return false
    }

    private fun colourful(argb: Int): Boolean {
        val r = argb shr 16 and 0xff; val g = argb shr 8 and 0xff; val bl = argb and 0xff
        return maxOf(r, g, bl) - minOf(r, g, bl) >= 40
    }

    private fun buzz() { b.terminal.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) { bumpFont(if (scale > 1f) 1 else -1); return 1f }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            b.ime.resetIme()
            if (e != null && Prefs.tapActions && handleTap(e)) return
            focusInput(true)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = Prefs.charInput
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?): Boolean { event?.let { startSelect(it) }; return true }
        override fun readControlKey() = ctrlSticky || volCtrl
        override fun readAltKey() = altSticky
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            b.terminal.post { clearSticky(); toBottom() }
            return false
        }
        override fun onEmulatorSet() {
            current?.let { Themes.apply(it.terminal.emulator, it.host.theme.ifBlank { Prefs.theme }) }
            syncSize()
        }
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
