// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

/**
 * A row of the screen as runs of text with one look each, its colours
 * resolved (ARGB): what a view that draws text, not cells, needs (the iOS
 * app's, in SwiftUI). As Android's TerminalView resolves them: the
 * remote's palette changes, bold as bright, iCE colours (blink as a bright
 * background), inverse (the cell's and the screen's), faint.
 */
class StyledRun(
    /** its first column, and the columns it covers (a wide character two) */
    val col: Int,
    val cells: Int,
    /** its text: never-written cells as spaces, a wide character once, combining marks after their base */
    val text: String,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
)

/** how the runs' colours are taken (the terminal's settings) */
class RunLook(
    val theme: TermTheme = TermTheme.VGA,
    /** bold in colours 0-7 as their bright ones (8-15), the BBS way */
    val boldBright: Boolean = true,
    /** blink as a bright background, not blinking (iCE colours) */
    val iceColors: Boolean = false,
)

/** [row] of the screen (0: its top; negative: the scrollback's, -1 the newest) as runs */
fun TerminalEmulator.runs(row: Int, look: RunLook = RunLook()): List<StyledRun> {
    val line = line(row)
    val out = ArrayList<StyledRun>()
    val text = StringBuilder()

    /** colour [c] as ARGB: the remote's override of it if it set one, else the theme's */
    fun color(c: Int, isFg: Boolean): Int {
        val i = when {
            c == Style.DEFAULT_FG -> 256
            c == Style.DEFAULT_BG -> 257
            c and Style.RGB == 0 && c in 0..255 -> c
            else -> -1
        }
        if (i >= 0) {
            val o = colorOverride(i)
            if (o != -1) return o
        }
        return look.theme.argb(c, isFg)
    }

    var x = 0
    while (x < line.cols) {
        val st = line.styles[x]
        var end = x + 1
        while (end < line.cols && line.styles[end] == st) end++
        val a = Style.attrs(st)
        var fg = Style.fg(st)
        var bg = Style.bg(st)
        if (a and Style.BOLD != 0 && look.boldBright && fg in 0..7) fg += 8
        if (look.iceColors && a and Style.BLINK != 0 && bg in 0..7) bg += 8
        var f = color(fg, true)
        var b = color(bg, false)
        if ((a and Style.INVERSE != 0) != reverseVideo) {
            val t = f; f = b; b = t
        }
        if (a and Style.FAINT != 0) f = (f and 0x00ffffff) or (0x99 shl 24)
        val hidden = a and Style.INVISIBLE != 0
        text.setLength(0)
        for (i in x until end) {
            val cp = line.chars[i]
            when {
                cp == Line.WIDE_TAIL -> {}
                hidden || cp == 0 -> text.append(' ')
                else -> {
                    text.appendCp(cp)
                    line.extra(i)?.combining?.forEach { text.appendCp(it) }
                }
            }
        }
        out.add(
            StyledRun(
                col = x, cells = end - x, text = text.toString(), fg = f, bg = b,
                // (heavy glyphs only when bold isn't shown as a bright colour instead)
                bold = a and Style.BOLD != 0 && !(look.boldBright && Style.fg(st) in 0..7),
                italic = a and Style.ITALIC != 0,
                underline = a and Style.ANY_UNDERLINE != 0,
                strike = a and Style.STRIKE != 0,
            ),
        )
        x = end
    }
    return out
}
