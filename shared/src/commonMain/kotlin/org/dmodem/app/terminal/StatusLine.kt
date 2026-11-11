// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A VT320's host-writable status line (DECSSDT 2): one line below the
 * screen that the host writes to after DECSASD 1, with a cursor of its
 * own that moves only along it.
 */
class StatusLine(cols: Int) {
    val line = Line(cols)
    /** the cursor's column on it */
    var x = 0
        private set

    /** [cp] written at the cursor in [style], the cursor after it (staying at the last column) */
    fun print(cp: Int, style: Long) {
        line.set(x, cp, style)
        if (x < line.cols - 1) x++
    }

    /** the cursor to column [col] (from 0), kept on the line */
    fun moveTo(col: Int) {
        x = col.coerceIn(0, line.cols - 1)
    }

    /** EL on it: right of the cursor (0), left of it (1), all of it (2) */
    fun erase(mode: Int, style: Long) {
        when (mode) {
            0 -> line.clear(x, line.cols, style)
            1 -> line.clear(0, x + 1, style)
            2 -> line.clear(0, line.cols, style)
        }
    }

    /** its columns made [cols] */
    fun resize(cols: Int) {
        line.resize(cols)
        x = x.coerceIn(0, cols - 1)
    }

    /** blank, the cursor at its start */
    fun clear() {
        line.blank(Style.NORMAL)
        x = 0
    }
}
