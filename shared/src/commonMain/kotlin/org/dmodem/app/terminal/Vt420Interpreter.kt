// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.roundToInt

/**
 * A VT420's and VT5xx's sequences over the VT320's: left and right
 * margins (DECLRMM, DECSLRM), the rectangular area operations, column
 * insert and delete (DECIC, DECDC) and the back and forward index (DECBI,
 * DECFI), pages (NP, PP, PPA, PPR, PPB), the lines and columns asked for
 * (DECSNLS, DECSLPP, DECSCPP), the keypad and backarrow modes (DECNKM,
 * DECBKM), and from the VT510 on the display extent (DECRQDE), the bell
 * volumes, and (a VT525's) the colour table (DECAC, DECRQTSR, DECRSTS).
 */
open class Vt420Interpreter(t: TerminalEmulator, profile: DecProfile) : VtInterpreter(t, profile) {
    private val rectangles = Rectangles(t)
    /** DECBKM: the backarrow key sends BS (set) or DEL */
    private var backarrowBs = false

    /** the VT420's ESC 6 (DECBI) and ESC 9 (DECFI) */
    override fun extraEsc(cp: Int): Boolean {
        if (operatingLevel < 4) return false
        when (cp.toChar()) {
            '6' -> backIndex()
            '9' -> forwardIndex()
            else -> return false
        }
        return true
    }

    /** DECBI: the cursor left one, or at the left margin the area between the margins moved right a column */
    private fun backIndex() {
        if (t.cursorX > t.left) {
            t.moveCursor(t.cursorX - 1, t.cursorY)
        } else if (t.cursorX == t.left && t.cursorY in t.top..t.bottom) {
            rectangles.insertColumns(t.left, 1)
        }
    }

    /** DECFI: the cursor right one, or at the right margin the area between the margins moved left a column */
    private fun forwardIndex() {
        if (t.cursorX < t.right) {
            t.moveCursor(t.cursorX + 1, t.cursorY)
        } else if (t.cursorX == t.right && t.cursorY in t.top..t.bottom) {
            rectangles.deleteColumns(t.left, 1)
        }
    }

    /** the VT420's and VT5xx's CSIs (each at the level that has it) */
    override fun extraCsi(final: Int, marker: Int, inter: Int): Boolean {
        if (operatingLevel < 4) return false
        val c = final.toChar()
        if (marker == '?'.code) {
            if (inter == 0 && (c == 'h' || c == 'l')) {
                // (the others still the parser's)
                for (i in 0 until maxOf(paramCount, 1)) mode(param(i), c == 'h')
            }
            return false
        }
        if (marker != 0) return false
        val i = inter.toChar()
        when {
            inter == 0 && c == 's' && t.lrMargins -> t.setMargins(p(0, 1) - 1, p(1, t.cols) - 1)
            inter == 0 && c == 'U' -> page(t.page + p(0, 1), home = true)
            inter == 0 && c == 'V' -> page(t.page - p(0, 1), home = true)
            i == ' ' && c == 'P' -> page(p(0, 1) - 1, home = false)
            i == ' ' && c == 'Q' -> page(t.page + p(0, 1), home = false)
            i == ' ' && c == 'R' -> page(t.page - p(0, 1), home = false)
            // DECIC, DECDC: only from between the margins and in the region
            i == '\'' && c == '}' -> if (t.withinMargins && t.cursorY in t.top..t.bottom) rectangles.insertColumns(t.cursorX, p(0, 1))
            i == '\'' && c == '~' -> if (t.withinMargins && t.cursorY in t.top..t.bottom) rectangles.deleteColumns(t.cursorX, p(0, 1))
            i == '$' && c == 'v' -> rectangles.area(::p)?.let { a ->
                // DECCRA: the area to Ptd; Pld (from the margins in origin mode)
                val oy = if (t.originMode) t.top else 0
                val ox = if (t.originMode) t.left else 0
                rectangles.copy(a, p(5, 1) - 1 + oy, p(6, 1) - 1 + ox)
            }
            i == '$' && c == 'z' -> rectangles.area(::p)?.let { rectangles.erase(it, selective = false) }
            i == '$' && c == '{' -> rectangles.area(::p)?.let { rectangles.erase(it, selective = true) }
            i == '$' && c == 'x' -> rectangles.area(::p, at = 1)?.let { a ->
                // DECFRA: a printable character only
                val ch = param(0)
                if (ch in 0x20..0x7e || ch in 0xa0..0xff) rectangles.fill(a, ch)
            }
            i == '$' && (c == 'r' || c == 't') -> rectangles.area(::p, stream = !rectangles.rectangle)?.let { a ->
                rectangles.attributes(a, (4 until paramCount).map { param(it) }, reverse = c == 't')
            }
            i == '*' && c == 'x' -> rectangles.rectangle = param(0) == 2
            // DECRQCRA: Pid; Pp; the area
            i == '*' && c == 'y' -> {
                val a = rectangles.area(::p, at = 2)
                val sum = if (a == null) 0 else rectangles.checksum(a)
                t.reply("\u001bP${param(0)}!~${sum.toString(16).uppercase().padStart(4, '0')}\u001b\\")
            }
            // DECSNLS: lines on the screen; DECSLPP (24 and more: lines a page); DECSCPP: 80 or 132 columns
            i == '*' && c == '|' -> t.requestedRows = p(0, 24).coerceIn(1, 255)
            inter == 0 && c == 't' && param(0) >= 24 -> t.requestedRows = param(0).coerceAtMost(255)
            i == '$' && c == '|' -> t.requestedColumns = if (p(0, 80) > 80) 132 else 80
            else -> return level5(i, c)
        }
        return true
    }

    /** a VT5xx's: the display extent (DECRQDE), the bell volumes, the keyboard's (taken), a VT525's colours */
    private fun level5(i: Char, c: Char): Boolean {
        if (operatingLevel < 5) return false
        when {
            i == '"' && c == 'v' -> t.reply("\u001b[${t.rows};${t.cols};1;1;${t.page + 1}\"w")
            // DECSWBV, DECSMBV, DECSKCV, DECTME: there's nothing here for them to set
            i == ' ' && (c == 't' || c == 'u' || c == '=' || c == '~') -> {}
            // DECAC: the normal text's colours (Ps1 1) from the table's 16
            i == ',' && c == '|' && profile.has(VtFeature.COLOR) -> if (param(0) == 1) {
                t.setColorOverride(256, color(param(1)))
                t.setColorOverride(257, color(param(2)))
            }
            // DECATC: the alternate text colours (drawn as the attributes' own)
            i == ',' && c == '}' -> {}
            // DECRQTSR 2: the colour table (DECCTR)
            i == '$' && c == 'u' && param(0) == 2 && profile.has(VtFeature.COLOR) -> t.reply(colorTable())
            else -> return false
        }
        return true
    }

    /** colour [n] of the 16 as the screen shows it now */
    private fun color(n: Int): Int {
        val i = n.coerceIn(0, 15)
        return t.colorOverride(i).takeIf { it != TerminalEmulator.NO_COLOR } ?: t.defaultColor(i)
    }

    /** DECCTR: the 16 colours, each Pc; 2 (RGB); its red, green and blue in percent */
    private fun colorTable(): String {
        /** a channel (0-255) in percent */
        fun pct(v: Int) = (v * 100 / 255.0).roundToInt()
        val entries = (0 until 16).joinToString("/") { n ->
            val c = color(n)
            "$n;2;${pct((c shr 16) and 0xff)};${pct((c shr 8) and 0xff)};${pct(c and 0xff)}"
        }
        return "\u001bP2\$s$entries\u001b\\"
    }

    /** DECRSTS ($p) with Ps 2: the colour table restored from DECCTR's entries (Pu 1 HLS, 2 RGB) */
    override fun wantsDcs(final: Int, inter: Int): Boolean =
        final == 'p'.code && inter == '$'.code && operatingLevel >= 5 && profile.has(VtFeature.COLOR)

    /** the text of a DCS [wantsDcs] took: DECRSTS's colour table */
    override fun extraDcs(kind: String, params: IntArray, text: String) {
        if (kind != "\$p" || params.getOrElse(0) { 0 } != 2) return
        for (entry in text.split('/')) {
            val f = entry.split(';').map { it.toIntOrNull() ?: 0 }
            if (f.size < 5 || f[0] !in 0..15) continue
            val argb = when (f[1]) {
                1 -> Sixel.hls(f[2], f[3], f[4])
                2 -> rgb(f[2], f[3], f[4])
                else -> continue
            }
            t.setColorOverride(f[0], argb)
        }
    }

    /** red, green and blue in percent as a colour */
    private fun rgb(r: Int, g: Int, b: Int): Int {
        /** a percentage as 0-255 */
        fun c(v: Int) = (v.coerceIn(0, 100) * 255 / 100.0).roundToInt()
        return (0xff shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** page [n] (from 0, kept to the 6 there are) gone to */
    private fun page(n: Int, home: Boolean) {
        t.selectPage(n.coerceIn(0, PAGES - 1), home)
    }

    /** a VT420's DEC modes: DECNKM (66), DECBKM (67), DECLRMM (69); DECKPM (81) taken */
    private fun mode(m: Int, on: Boolean) {
        when (m) {
            66 -> t.appKeypad = on
            67 -> backarrowBs = on
            69 -> t.lrMargins = on
        }
    }

    /** DECRQM's answers for the modes [mode] has */
    override fun extraModeState(m: Int): Int {
        if (operatingLevel < 4) return 0
        /** DECRQM's 1 for set, 2 for reset */
        fun b(v: Boolean) = if (v) 1 else 2
        return when (m) {
            66 -> b(t.appKeypad)
            67 -> b(backarrowBs)
            69 -> b(t.lrMargins)
            else -> 0
        }
    }

    /** DECRQSS for the margins (DECSLRM) and DECSACE */
    override fun extraStatus(q: String): String? = when {
        operatingLevel < 4 -> null
        q == "s" -> "${t.left + 1};${t.right + 1}s"
        q == "*x" -> "${if (rectangles.rectangle) 2 else 1}*x"
        else -> null
    }

    /** DECBKM set: the backarrow key sends BS; else as ever (the user's setting, the VT320's keys) */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        if (k == Key.BACKSPACE && backarrowBs && !alt && !ctrl) return byteArrayOf(0x08)
        return super.keyOctets(k, shift, alt, ctrl)
    }

    /** DECSTR: DECSACE back to the stream, the backarrow as at the start */
    override fun softReset() {
        super.softReset()
        rectangles.rectangle = false
        backarrowBs = false
    }

    companion object {
        /** the pages a VT420 has */
        const val PAGES = 6
    }
}
