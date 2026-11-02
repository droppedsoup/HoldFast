package com.holdfast.ssh

import com.holdfast.ssh.util.MenuTap
import com.holdfast.ssh.util.TapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTest {
    private val menu = listOf(
        "Which approach?",
        "",
        "❯ 1. Fast",
        "     Quick but rough",
        "  2. Careful",
        "     Slower",
        "  3. Type something",
        "",
        "Enter to select · ↑/↓ to navigate",
    )

    @Test fun menuArrows() {
        assertEquals(0, MenuTap.arrows(menu, 2))
        assertEquals(1, MenuTap.arrows(menu, 4))
        assertEquals(1, MenuTap.arrows(menu, 5)) // description line belongs to option 2
        assertEquals(2, MenuTap.arrows(menu, 6))
        assertNull(MenuTap.arrows(menu, 0))
        val up = menu.map { it.replace("❯", " ") }.toMutableList().also { it[6] = "❯ 3. Type something" }
        assertEquals(-2, MenuTap.arrows(up, 2))
    }

    @Test fun noMenuWithoutCursor() {
        assertNull(MenuTap.arrows(listOf("1. first", "2. second"), 0))
    }

    @Test fun links() {
        assertEquals("https://example.com/a", TapText.urlAt("see https://example.com/a.", 8, 80))
        assertNull(TapText.urlAt("see https://example.com/a", 1, 80))
        val row = "go https://ex.com/ab"
        assertEquals("https://ex.com/abcd", TapText.urlAt(row, 5, row.length, "cd more"))
    }

    @Test fun inlineCode() {
        val t = "run gradle build now"
        val fg = IntArray(t.length) { if (it in 4..15) 5 else 256 }
        assertEquals("gradle build", TapText.codeAt(t, fg, 6))
        assertNull(TapText.codeAt(t, fg, 1))
        assertNull(TapText.codeAt(t, fg, 3))
        val whole = "! gcloud auth login"
        assertEquals(whole, TapText.codeAt(whole, IntArray(whole.length) { 4 }, 5))
    }
}
