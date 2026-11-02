package com.holdfast.ssh.util

/**
 * Turns "what the keyboard's text box now holds" into terminal keystrokes, given what was already typed:
 * backspace over the part that changed, then type the new tail. This lets autocorrect / swipe typing
 * rewrite a word that is already on the remote prompt.
 */
object ImeDiff {
    /** @return number of backspaces to send, then the text to type. */
    fun diff(sent: String, now: String): Pair<Int, String> {
        var i = 0
        val n = minOf(sent.length, now.length)
        while (i < n && sent[i] == now[i]) i++
        // Never split a surrogate pair.
        if (i > 0 && Character.isHighSurrogate(sent[i - 1]) && (i >= sent.length || i >= now.length || sent[i] != now[i])) i--
        val removed = sent.substring(i)
        return removed.codePointCount(0, removed.length) to now.substring(i)
    }

    /** Word (letters/digits/_ or a run of other non-space chars) around [off] in [text], as [start, end). */
    fun wordAt(text: String, off: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val o = off.coerceIn(0, text.length - 1)
        val c = text[o]
        if (c.isWhitespace()) return o to o + 1
        val cls: (Char) -> Boolean = if (c.isLetterOrDigit() || c == '_') { ch -> ch.isLetterOrDigit() || ch == '_' }
            else { ch -> !ch.isWhitespace() && !(ch.isLetterOrDigit() || ch == '_') }
        var s = o; var e = o + 1
        while (s > 0 && cls(text[s - 1])) s--
        while (e < text.length && cls(text[e])) e++
        return s to e
    }
}
