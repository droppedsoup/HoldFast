package com.holdfast.ssh.util

/**
 * Tap-to-pick for numbered TUI menus (Claude Code's "❯ 1. Yes / 2. No" prompts).
 * Given the visible screen lines and the tapped line, returns how many arrow presses
 * (negative = up) move the ❯ cursor onto the tapped option, or null if the tap isn't on a menu.
 */
object MenuTap {
    private val option = Regex("""^\s*(?:[│|]\s*)?(❯|›|>)?\s*(?:\[[ x✓✔]?]\s*)?(\d{1,2})\.\s+\S""")

    fun arrows(lines: List<String>, tapped: Int): Int? {
        if (tapped !in lines.indices) return null
        data class Opt(val row: Int, val num: Int, val selected: Boolean)
        val opts = lines.mapIndexedNotNull { i, l ->
            option.find(l)?.let { Opt(i, it.groupValues[2].toInt(), it.groupValues[1].isNotEmpty()) }
        }
        val sel = opts.lastOrNull { it.selected } ?: return null
        // The menu = the run of consecutively numbered options around the selected one.
        val block = mutableListOf(sel)
        var i = opts.indexOf(sel)
        while (i > 0 && opts[i - 1].num == block.first().num - 1 && opts[i - 1].row < block.first().row) { block.add(0, opts[i - 1]); i-- }
        i = opts.indexOf(sel)
        while (i < opts.size - 1 && opts[i + 1].num == block.last().num + 1) { block.add(opts[i + 1]); i++ }
        // Tapped option = last option at or above the tap (covers wrapped/description lines), within 3 lines of the last one.
        val target = block.lastOrNull { it.row <= tapped } ?: return null
        val next = block.getOrNull(block.indexOf(target) + 1)
        if (next == null && tapped - target.row > 3) return null
        return block.indexOf(target) - block.indexOf(sel)
    }
}

/** One-tap links and tap-to-paste of highlighted (inline-code) text. Pure so it can be unit tested. */
object TapText {
    private val url = Regex("""https?://[^\s"'<>│]+""")

    /** URL under [col] in [line]; [next] is the following row, joined when the URL runs to the row's end (wrapped). */
    fun urlAt(line: String, col: Int, cols: Int, next: String? = null): String? {
        val m = url.findAll(line).firstOrNull { col in it.range } ?: return null
        var u = m.value
        if (m.range.last >= cols - 1 && next != null) u += next.takeWhile { !it.isWhitespace() && it != '│' }
        return u.trimEnd('.', ',', ')', ']', '`', '\'')
    }

    /**
     * Text run under [col] drawn in a non-default foreground colour ([plain] = default index),
     * e.g. Claude's `inline code`. Returns null on plain text, spaces, or runs shorter than 2 chars.
     */
    fun codeAt(text: String, fg: IntArray, col: Int, plain: Int = 256): String? {
        if (col !in text.indices || text[col].isWhitespace()) return null
        val c = fg[col]
        if (c == plain) return null
        var a = col; var z = col
        while (a > 0 && fg[a - 1] == c) a--
        while (z < text.length - 1 && fg[z + 1] == c) z++
        val s = text.substring(a, z + 1).trim()
        return s.takeIf { it.length >= 2 }
    }
}
