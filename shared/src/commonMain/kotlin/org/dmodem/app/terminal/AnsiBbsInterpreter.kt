// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A PC BBS terminal as SyncTERM's CTerm is: the VT parser (xterm's, as
 * the BBSes reached as ANSI expect) with what CTerm adds: an 80x25
 * screen, ANSI music, iCE colours on request (CSI ? 33 h), the line speed
 * shown for ANSI animations (CSI * r), doorway mode for DOS doors (CSI =
 * 255 h: the keys as a PC's scan codes), and font selection (CSI SP D:
 * only code page 437 is there, so the others are taken and ignored).
 */
open class AnsiBbsInterpreter(t: TerminalEmulator, profile: AnsiBbsProfile) : VtInterpreter(t, profile) {
    /** which CSIs start ANSI music (CSI = n M): 0 only CSI |, 1 CSI N too, 2 CSI M too (unless it has parameters: then it's DL) */
    private var musicMode = 2
    /** the music being gathered, until SO; null outside it */
    private var music: StringBuilder? = null
    /** a bare CSI M came: music if what follows starts a PLAY string, else DL */
    private var maybeMusic = false
    private val player = AnsiMusic()
    /** doorway mode: the keys as NUL and a PC's scan code */
    private var doorway = false

    init {
        attach()
    }

    /** the PC's 80x25 */
    private fun attach() {
        t.requestedColumns = 80
        t.requestedRows = 25
    }

    /** the view's size back, for the profile taking over */
    override fun detach() {
        t.requestedColumns = 0
        t.requestedRows = 0
        t.iceColors = false
        t.emulatedBps = 0
    }

    /** RIS: the music, doorway mode and the line speed as at the start, and the 80x25 asked for again */
    override fun reset() {
        super.reset()
        musicMode = 2
        music = null
        maybeMusic = false
        player.reset()
        doorway = false
        attach()
    }

    /** the music's characters gathered, to the SO that plays them */
    override fun intercept(cp: Int): Boolean {
        if (maybeMusic) {
            maybeMusic = false
            if (cp.toChar() in MUSIC_STARTS) {
                // (the CSI's M is the string's too: ESC [ M F is PLAY's MF)
                music = StringBuilder("M")
            } else {
                t.deleteLines(1)
                return false
            }
        }
        val m = music ?: return false
        when {
            cp == 0x0e -> {
                music = null
                val notes = player.parse(m.toString())
                if (notes.isNotEmpty()) t.listener?.onMusic(notes)
            }
            // (a string that never ends: given up)
            m.length > 4096 || cp == 0x1b || cp == 0x18 -> music = null
            else -> m.appendCp(cp)
        }
        return cp != 0x1b
    }

    /** CTerm's: music's start and mode, iCE colours, doorway mode, the line speed, font selection */
    override fun extraCsi(final: Int, marker: Int, inter: Int): Boolean {
        val c = final.toChar()
        when {
            marker == 0 && inter == 0 && (c == '|' || c == 'N' && musicMode >= 1) -> {
                music = StringBuilder()
                return true
            }
            marker == 0 && inter == 0 && c == 'M' && musicMode == 2 && paramCount == 0 -> {
                maybeMusic = true
                return true
            }
            marker == '='.code && c == 'M' -> {
                musicMode = p(0, 0).coerceIn(0, 2)
                return true
            }
            marker == '='.code && (c == 'h' || c == 'l') -> {
                if (param(0) == 255) doorway = c == 'h'
                return true
            }
            // (the rest of a ? h / l still the parser's)
            marker == '?'.code && (c == 'h' || c == 'l') -> {
                for (i in 0 until maxOf(paramCount, 1)) if (param(i) == 33) t.iceColors = c == 'h'
                return false
            }
            marker == 0 && inter == '*'.code && c == 'r' -> {
                // (Ps1 0 or 1: the line the speed is for, both the one here)
                if (param(0) <= 1) t.emulatedBps = SPEEDS.getOrElse(param(1)) { 0 }
                return true
            }
            marker == 0 && inter == ' '.code && c == 'D' -> return true
        }
        return false
    }

    /** in doorway mode, a key as NUL and its scan code (Alt with a letter its own); otherwise the VT sequences */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        if (!doorway) return null
        val code = SCAN_CODES[k] ?: return null
        return byteArrayOf(0, code.toByte())
    }

    /** in doorway mode, Alt and a letter or digit as NUL and the key's scan code; otherwise as typed */
    override fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? {
        if (!doorway || !alt) return null
        val c = if (cp in 'a'.code..'z'.code) cp - 32 else cp
        val code = ALT_SCAN_CODES.indexOf(c.toChar()).takeIf { it >= 0 }?.let { ALT_SCAN_BASE[it] } ?: return null
        return byteArrayOf(0, code.toByte())
    }

    companion object {
        /** what a PLAY string starts with: a note, a command, an octave change */
        private const val MUSIC_STARTS = "ABCDEFGLMNOPT<>"

        /** CSI * r's speeds: 0 unlimited, then 300 to 115200 */
        private val SPEEDS = intArrayOf(0, 300, 600, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 76800, 115200)

        /** the keys' scan codes, as a PC keyboard's BIOS gives them */
        private val SCAN_CODES = mapOf(
            Key.UP to 0x48, Key.DOWN to 0x50, Key.LEFT to 0x4b, Key.RIGHT to 0x4d,
            Key.HOME to 0x47, Key.END to 0x4f, Key.PAGE_UP to 0x49, Key.PAGE_DOWN to 0x51,
            Key.INSERT to 0x52, Key.DELETE to 0x53,
            Key.F1 to 0x3b, Key.F2 to 0x3c, Key.F3 to 0x3d, Key.F4 to 0x3e, Key.F5 to 0x3f,
            Key.F6 to 0x40, Key.F7 to 0x41, Key.F8 to 0x42, Key.F9 to 0x43, Key.F10 to 0x44,
            Key.F11 to 0x85, Key.F12 to 0x86,
        )

        /** the letters and digits with Alt, and (at the same place in [ALT_SCAN_BASE]) their keys' scan codes */
        private const val ALT_SCAN_CODES = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890"
        private val ALT_SCAN_BASE = intArrayOf(
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
            0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
            0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32,
            0x78, 0x79, 0x7a, 0x7b, 0x7c, 0x7d, 0x7e, 0x7f, 0x80, 0x81,
        )
    }
}
