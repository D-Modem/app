// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/** The keys that aren't characters. */
enum class Key {
    UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    TAB, ENTER, BACKSPACE, ESCAPE,
}

/** What Enter sends. */
enum class EnterMode(val bytes: String, val label: String) {
    CR("\r", "CR"), CRLF("\r\n", "CR LF"), LF("\n", "LF"),
}

/** What Backspace sends: DEL (xterm's, Unix) or BS (^H, most BBSes and DOS). */
enum class BackspaceMode(val code: Int, val label: String) {
    DEL(0x7f, "DEL (^?)"), BS(0x08, "BS (^H)"),
}

/**
 * Keys as the octets a VT220/xterm sends: the cursor keys in normal or
 * application mode, xterm's modifier parameters (ESC[1;5A for Ctrl+Up),
 * Ctrl and Alt on characters.
 */
class KeyEncoder {
    var enter = EnterMode.CR
    var backspace = BackspaceMode.DEL

    /** xterm's modifier parameter: 1 + Shift 1 + Alt 2 + Ctrl 4 */
    private fun mod(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /** what [k] sends, with its modifiers ([appCursor]: the cursor keys in application mode) */
    fun key(k: Key, appCursor: Boolean, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val m = mod(shift, alt, ctrl)
        /** a cursor key's: CSI with the modifiers, SS3 in application mode, else CSI */
        fun cursor(c: Char) = when {
            m > 1 -> "\u001b[1;$m$c"
            appCursor -> "\u001bO$c"
            else -> "\u001b[$c"
        }
        /** a CSI n ~ key's, with the modifiers */
        fun tilde(n: Int) = if (m > 1) "\u001b[$n;$m~" else "\u001b[$n~"
        /** an SS3 function key's (CSI 1;m with modifiers) */
        fun ss3(c: Char) = if (m > 1) "\u001b[1;$m$c" else "\u001bO$c"
        val prefix = if (alt && k in setOf(Key.TAB, Key.ENTER, Key.BACKSPACE, Key.ESCAPE)) "\u001b" else ""
        return when (k) {
            Key.UP -> cursor('A')
            Key.DOWN -> cursor('B')
            Key.RIGHT -> cursor('C')
            Key.LEFT -> cursor('D')
            Key.HOME -> cursor('H')
            Key.END -> cursor('F')
            Key.INSERT -> tilde(2)
            Key.DELETE -> tilde(3)
            Key.PAGE_UP -> tilde(5)
            Key.PAGE_DOWN -> tilde(6)
            Key.F1 -> ss3('P')
            Key.F2 -> ss3('Q')
            Key.F3 -> ss3('R')
            Key.F4 -> ss3('S')
            Key.F5 -> tilde(15)
            Key.F6 -> tilde(17)
            Key.F7 -> tilde(18)
            Key.F8 -> tilde(19)
            Key.F9 -> tilde(20)
            Key.F10 -> tilde(21)
            Key.F11 -> tilde(23)
            Key.F12 -> tilde(24)
            Key.TAB -> if (shift) "\u001b[Z" else prefix + "\t"
            Key.ENTER -> prefix + enter.bytes
            Key.BACKSPACE -> prefix + (if (ctrl) "\u0008" else backspace.code.toChar().toString())
            Key.ESCAPE -> prefix + "\u001b"
        }
    }

    /**
     * A character typed: with Ctrl its control code (Ctrl+A ^A .. Ctrl+_ ^_,
     * Ctrl+Space and Ctrl+@ NUL, Ctrl+? DEL), with Alt ESC before it.
     */
    fun char(cp: Int, alt: Boolean = false, ctrl: Boolean = false): String {
        var c = cp
        if (ctrl) {
            c = when (c) {
                in 'a'.code..'z'.code -> c - 'a'.code + 1
                in '@'.code..'_'.code -> c - '@'.code
                ' '.code, '2'.code -> 0
                '3'.code -> 0x1b
                '4'.code -> 0x1c
                '5'.code -> 0x1d
                '6'.code -> 0x1e
                '7'.code, '/'.code -> 0x1f
                '8'.code, '?'.code -> 0x7f
                else -> c
            }
        }
        val s = codePointString(c)
        return if (alt) "\u001b" + s else s
    }
}

/** Links in the screen's text. */
object UrlFinder {
    private val URL = Regex(
        """(?:https?|ftp|telnet|ssh|gopher)://[^\s<>"'`]+|www\.[^\s<>"'`]+""",
        RegexOption.IGNORE_CASE,
    )

    /** the link that covers [col] of [text], or null; with what was trailing punctuation dropped */
    fun at(text: String, col: Int): String? {
        for (m in URL.findAll(text)) {
            val url = trim(m.value)
            if (col >= m.range.first && col < m.range.first + url.length) {
                return if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url
            }
        }
        return null
    }

    /** every link in [text], trailing punctuation dropped */
    fun all(text: String): List<String> = URL.findAll(text).map { trim(it.value) }.toList()

    /** [u] without trailing punctuation, nor closing brackets it didn't open */
    private fun trim(u: String): String {
        var s = u.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
        // a closing bracket the link didn't open
        while (s.isNotEmpty() && s.last() in ")]}" &&
            s.count { it == s.last() } > s.count { it == mapOf(')' to '(', ']' to '[', '}' to '{')[s.last()] }
        ) {
            s = s.dropLast(1)
        }
        return s
    }
}
