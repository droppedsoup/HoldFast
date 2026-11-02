package com.holdfast.ssh.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.holdfast.ssh.util.ImeDiff
import com.termux.view.TerminalView

/**
 * Invisible view that holds keyboard focus instead of the (final) TerminalView and talks to the keyboard like a
 * normal text box, so autocorrect, suggestions and swipe typing work straight at the prompt. Letters appear as you
 * type; when the keyboard rewrites a word the change is replayed as backspaces + the corrected text.
 */
class ImeProxyView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    lateinit var term: TerminalView
    var ctrlOrAltSticky: () -> Boolean = { false }
    private var conn: Conn? = null

    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onCheckIsTextEditor() = true
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean = term.onKeyDown(keyCode, e)
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = term.onKeyUp(keyCode, e)

    override fun onCreateInputConnection(o: EditorInfo): InputConnection {
        o.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        o.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        o.initialSelStart = 0; o.initialSelEnd = 0
        return Conn().also { conn = it }
    }

    /** Forget the word being typed (cursor moved, Enter, extra key…) so autocorrect never rewrites stale text. */
    fun resetIme() {
        val c = conn ?: return
        if (c.isClean()) return
        c.clear()
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    private inner class Conn : BaseInputConnection(this@ImeProxyView, true) {
        private var sent = ""

        fun isClean() = sent.isEmpty() && (editable?.length ?: 0) == 0
        fun clear() { sent = ""; editable?.clear() }

        private fun del(n: Int) = repeat(n) { term.handleKeyCode(KeyEvent.KEYCODE_DEL, 0) }

        private fun type(s: String) {
            val modded = ctrlOrAltSticky()
            s.codePoints().forEach { cp -> term.inputCodePoint(if (cp == '\n'.code) '\r'.code else cp, false, false) }
            // A Ctrl/Alt chord isn't text, so it must not be in the keyboard's buffer.
            if (modded && s.isNotEmpty()) { sent = ""; editable?.clear(); post { resetIme() } }
        }

        /** Push the buffer's current contents to the terminal. Enter (newline) flushes the buffer. */
        private fun sync() {
            val ed = editable ?: return
            val now = ed.toString()
            val nl = now.indexOf('\n')
            if (nl < 0) {
                val (bs, ins) = ImeDiff.diff(sent, now)
                del(bs); sent = now; type(ins)
                return
            }
            val (bs, ins) = ImeDiff.diff(sent, now.substring(0, nl))
            del(bs); type(ins)
            term.handleKeyCode(KeyEvent.KEYCODE_ENTER, 0)
            sent = ""; ed.clear()
            post { context.getSystemService(InputMethodManager::class.java)?.restartInput(this@ImeProxyView) }
        }

        override fun commitText(text: CharSequence?, pos: Int): Boolean { super.commitText(text, pos); sync(); return true }
        override fun setComposingText(text: CharSequence?, pos: Int): Boolean { super.setComposingText(text, pos); sync(); return true }
        override fun finishComposingText(): Boolean { super.finishComposingText(); sync(); return true }
        override fun setSelection(start: Int, end: Int): Boolean = true // terminal cursor can't follow; keep it at the end

        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            val ed = editable
            val have = ed?.length ?: 0
            if (have == 0) { del(before); return true }
            val n = minOf(before, have)
            ed!!.delete(have - n, have)
            sync()
            if (before > n) { del(before - n) }
            return true
        }

        override fun sendKeyEvent(e: KeyEvent): Boolean {
            val ed = editable
            when (e.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        val len = ed?.length ?: 0
                        if (len > 0) { ed!!.delete(len - 1, len); sync() } else del(1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    if (e.action == KeyEvent.ACTION_DOWN) { ed?.append('\n'); sync() }
                    return true
                }
            }
            if (e.action == KeyEvent.ACTION_DOWN) clear()
            return super.sendKeyEvent(e)
        }
    }
}
