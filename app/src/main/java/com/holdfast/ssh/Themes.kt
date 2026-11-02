package com.holdfast.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle

/** Original HoldFast terminal palettes: fg, bg, cursor, then ANSI 0-15. */
object Themes {
    const val DEFAULT = "Harbor"

    private fun c(vararg hex: String) = hex.map { (0xFF000000 or it.toLong(16)).toInt() }.toIntArray()

    val all: Map<String, IntArray> = linkedMapOf(
        "Harbor" to c(
            "D8E2EA", "0E1A24", "3DDBC4",
            "1B2833", "E0646B", "6FCB8E", "E8C46A", "5AA2E6", "B888E0", "3DC9C4", "C5D0D9",
            "4A5C6B", "FF8088", "8FE3A8", "F5D88A", "7DBBF5", "CDA6F0", "6FE3DC", "F2F6F9",
        ),
        "Ink" to c(
            "E6E6E6", "000000", "FFFFFF",
            "000000", "D4434C", "4FB86A", "D9B44A", "4C88D9", "A26ACF", "38B2B2", "BFBFBF",
            "555555", "F0626A", "6FD889", "F2CF68", "6EA6F2", "BE8CE6", "5CD1D1", "FFFFFF",
        ),
        "Paper" to c(
            "2B2B2B", "F7F5EF", "2B6CB0",
            "2B2B2B", "B83A3A", "2F7D3E", "8A6A12", "2B5FA8", "7A3EA3", "1F7A7A", "D9D6CC",
            "6B6B6B", "D24C4C", "3C9A4E", "A88420", "3B74C4", "9150BF", "2A9494", "FFFFFF",
        ),
        "Ember" to c(
            "F2E3D5", "1F1410", "FF9E57",
            "2A1C16", "E8604C", "A8C66C", "F0B04F", "6C9BD2", "D07AA8", "62B8A4", "D8C7B8",
            "5E4A3F", "FF7E6B", "C2DE88", "FFC970", "8CB4E6", "E69AC2", "80D2BE", "FFF5EC",
        ),
        "Moss" to c(
            "DCE5D2", "121A12", "9BD67A",
            "1C261C", "D96A5E", "84C46B", "D6C26A", "6E9FC2", "AE8CC8", "62B49A", "C3CDB9",
            "4C5C4C", "F08A7E", "A2DE88", "EDD98A", "8DB9DB", "C8A8E0", "82D0B6", "F1F6EC",
        ),
        "Dusk" to c(
            "E4DEF2", "1A1626", "F2A3D0",
            "262036", "EE6E8A", "7DD3A8", "EAC877", "7E9CF0", "C792EA", "6BC8DA", "CFC7E0",
            "51476A", "FF8FA6", "9DEBC2", "F7DC98", "9DB6FF", "DDB0F7", "8DE0EE", "F8F5FF",
        ),
    )

    fun names() = all.keys.toList()

    fun apply(emulator: TerminalEmulator?, name: String) {
        val pal = all[name] ?: all.getValue(DEFAULT)
        val colors = emulator?.mColors?.mCurrentColors ?: return
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = pal[0]
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = pal[1]
        colors[TextStyle.COLOR_INDEX_CURSOR] = pal[2]
        for (i in 0 until 16) colors[i] = pal[3 + i]
    }

    fun background(name: String) = (all[name] ?: all.getValue(DEFAULT))[1]
}
