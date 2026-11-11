// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The terminal as the iOS app's Swift has it: a [TerminalEmulator] and its
 * keys behind a few calls that pass plain values. Octets go both ways as
 * Latin-1 strings (one character for each octet: Swift's Data to and from
 * them is exact and cheap), the screen as [StyledRun]s.
 */
class TerminalBridge(cols: Int, rows: Int) {
    private val replies = StringBuilder()
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    val emulator = TerminalEmulator(cols, rows, output = { b ->
        kotlinx.atomicfu.locks.synchronized(lock) { for (x in b) replies.append((x.toInt() and 0xff).toChar()) }
    })
    private val keys = KeyEncoder()
    private val music = ArrayList<MusicNote>()
    private var look = RunLook()

    /** the window's title (OSC 0/2), as the remote last set it */
    var title = ""
        private set

    init {
        emulator.listener = object : TerminalEmulator.Listener {
            /** the remote's title kept */
            override fun onTitle(title: String) { this@TerminalBridge.title = title }

            /** ANSI music kept for [takeMusic] */
            override fun onMusic(notes: List<MusicNote>) {
                kotlinx.atomicfu.locks.synchronized(lock) { music.addAll(notes) }
            }
        }
    }

    val cols: Int get() = emulator.cols
    val rows: Int get() = emulator.rows
    val cursorX: Int get() = emulator.cursorX
    val cursorY: Int get() = emulator.cursorY
    val cursorVisible: Boolean get() = emulator.cursorVisible
    /** changes with each change to the screen: redraw when it does */
    val revision: Long get() = emulator.revision

    /** the remote's octets (Latin-1, one character each) onto the screen */
    fun feed(latin1: String) {
        val b = ByteArray(latin1.length) { latin1[it].code.toByte() }
        emulator.append(b, b.size)
    }

    /** what the terminal answers the remote (cursor reports, its identity …), as octets in Latin-1; taken */
    fun takeReplies(): String = kotlinx.atomicfu.locks.synchronized(lock) {
        replies.toString().also { replies.setLength(0) }
    }

    /** ANSI music the remote sent since the last call, to play in order; taken */
    fun takeMusic(): List<MusicNote> = kotlinx.atomicfu.locks.synchronized(lock) {
        music.toList().also { music.clear() }
    }

    /** the line speed the remote asked to be shown at (CTerm's CSI * r), bits a second; 0: as it comes */
    val emulatedBps: Int get() = emulator.emulatedBps

    /** a row (0: the screen's top) as runs */
    fun runs(row: Int): List<StyledRun> = emulator.runs(row, look)

    /** the screen's background, the theme's (ARGB) */
    val background: Int get() = look.theme.bgArgb
    val cursorColor: Int get() = look.theme.cursorArgb

    /** the screen made [cols] x [rows] */
    fun resize(cols: Int, rows: Int) = emulator.resize(cols, rows)

    /** the theme ([TermTheme.ALL]'s ids), bold as bright, and the character set (UTF8, CP437, LATIN1) */
    fun setLook(themeId: String, boldBright: Boolean, iceColors: Boolean, charset: String) {
        look = RunLook(TermTheme.byId(themeId), boldBright, iceColors)
        emulator.decoder.charset = TermCharset.entries.firstOrNull { it.name == charset } ?: TermCharset.UTF8
    }

    /** the kind of terminal it is: a [TerminalProfiles] id (one not known: the default) */
    var profile: String
        get() = emulator.profile.id
        set(v) { emulator.profile = TerminalProfiles.byId(v) ?: TerminalProfiles.DEFAULT }

    /** the profiles' ids, in the pickers' order */
    val profileIds: List<String> get() = TerminalProfiles.all.map { it.id }

    /** profile [id]'s name, for a picker */
    fun profileLabel(id: String): String = TerminalProfiles.byId(id)?.label ?: id

    /** text typed: its octets in the character set (Latin-1, one character each); a newline as Enter */
    fun typed(text: String, ctrl: Boolean = false, alt: Boolean = false): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp = if (c.isHighSurrogate() && i + 1 < text.length) {
                i++
                0x10000 + ((c.code - 0xd800) shl 10) + (text[i].code - 0xdc00)
            } else {
                c.code
            }
            i++
            val b = if (cp == '\n'.code || cp == '\r'.code) {
                emulator.keyOctets(keys, Key.ENTER, alt = alt)
            } else {
                emulator.charOctets(keys, cp, alt, ctrl)
            }
            out.append(latin1(b))
        }
        return out.toString()
    }

    /** a key that isn't a character ([Key]'s names: UP, ESCAPE, F1 …) as octets in Latin-1; "" for no such key */
    fun key(name: String, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val k = Key.entries.firstOrNull { it.name == name } ?: return ""
        return latin1(emulator.keyOctets(keys, k, shift, alt, ctrl))
    }

    /** the rows the view should have at most (a Commodore's 25), 0 for as many as fit */
    val requestedRows: Int get() = emulator.requestedRows

    /** whether the cells are drawn from bitmaps (the profile's machine's glyphs, 8 wide), not the font */
    val bitmapGlyphs: Boolean get() = emulator.glyphs != null

    /** the bitmap glyphs' height: 8, 16 for a VGA's (0: none) */
    val glyphHeight: Int get() = emulator.glyphs?.height ?: 0

    /** row [row] (0 top) of [cp]'s bitmap glyph, its leftmost pixel bit 7; -1 if the profile has no glyph for it (drawn with the font) */
    fun glyphRow(cp: Int, row: Int): Int {
        val g = emulator.glyphs ?: return -1
        if (!g.has(cp)) return -1
        return g.row(cp, row)
    }

    /** the swatch (ARGB) the key at ([row], [col]) of page [page] shows, 0 for none */
    fun keyBarColor(page: Int, row: Int, col: Int): Int = (keyAt(page, row, col) as? BarKey.Octets)?.color ?: 0

    /** row [row]'s size: 0 single, 1 double width, 2 double height's top half, 3 its bottom half */
    fun lineSize(row: Int): Int = emulator.line(row).size.ordinal

    /** the columns the remote asked for (DECCOLM: 80 or 132) for the view to fit, 0 for none */
    val requestedColumns: Int get() = emulator.requestedColumns

    /** the key bar's pages for the profile */
    private val bar: List<KeyBarPage> get() = emulator.profile.keyBar

    /** the key bar's pages */
    val keyBarPages: Int get() = bar.size

    /** page [page]'s rows of key labels (the next-page key's: the page it brings) */
    fun keyBarRows(page: Int): List<List<String>> {
        val p = bar.getOrNull(page) ?: return emptyList()
        return p.rows.map { r -> r.map { k -> if (k is BarKey.NextPage) KeyBars.nextLabel(bar, page) else k.label } }
    }

    /** what the key at ([row], [col]) of page [page] is: "ctrl", "alt", "next" (page), or "" for one that sends */
    fun keyBarAction(page: Int, row: Int, col: Int): String = when (keyAt(page, row, col)) {
        BarKey.Ctrl -> "ctrl"
        is BarKey.Alt -> "alt"
        BarKey.NextPage -> "next"
        else -> ""
    }

    /** what the key at ([row], [col]) of page [page] sends, as octets in Latin-1 ("" for one that doesn't) */
    fun keyBarPress(page: Int, row: Int, col: Int, ctrl: Boolean = false, alt: Boolean = false): String =
        when (val k = keyAt(page, row, col)) {
            is BarKey.Send -> key(k.key.name, alt = alt, ctrl = ctrl)
            is BarKey.Type -> typed(k.text, ctrl, alt)
            is BarKey.Octets -> k.octets
            else -> ""
        }

    /** the bar's key at ([row], [col]) of page [page], or null */
    private fun keyAt(page: Int, row: Int, col: Int): BarKey? = bar.getOrNull(page)?.rows?.getOrNull(row)?.getOrNull(col)

    /** the screen's text from (row, col) to (row2, col2), for copying */
    fun text(row: Int, col: Int, row2: Int, col2: Int): String = emulator.text(row, col, row2, col2)

    /** octets as Latin-1 text (one character each) */
    private fun latin1(b: ByteArray): String = CharArray(b.size) { (b[it].toInt() and 0xff).toChar() }.concatToString()
}
