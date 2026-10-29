// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

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
    private var look = RunLook()

    /** the window's title (OSC 0/2), as the remote last set it */
    var title = ""
        private set

    init {
        emulator.listener = object : TerminalEmulator.Listener {
            /** the remote's title kept */
            override fun onTitle(title: String) { this@TerminalBridge.title = title }
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
            if (cp == '\n'.code || cp == '\r'.code) {
                out.append(keys.key(Key.ENTER, emulator.appCursorKeys, alt = alt))
                continue
            }
            out.append(keys.char(cp, alt, ctrl))
        }
        return octets(out.toString())
    }

    /** a key that isn't a character ([Key]'s names: UP, ESCAPE, F1 …) as octets in Latin-1; "" for no such key */
    fun key(name: String, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val k = Key.entries.firstOrNull { it.name == name } ?: return ""
        if (k == Key.BACKSPACE) return keys.backspace.code.toChar().toString()
        return octets(keys.key(k, emulator.appCursorKeys, shift, alt, ctrl))
    }

    /** the screen's text from (row, col) to (row2, col2), for copying */
    fun text(row: Int, col: Int, row2: Int, col2: Int): String = emulator.text(row, col, row2, col2)

    /** [s] in the character set, as Latin-1 text (one character an octet) */
    private fun octets(s: String): String {
        val b = emulator.decoder.charset.encode(s)
        return CharArray(b.size) { (b[it].toInt() and 0xff).toChar() }.concatToString()
    }
}
