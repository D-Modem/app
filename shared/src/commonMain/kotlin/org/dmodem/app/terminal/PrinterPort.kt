// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A DEC terminal's printer port, for the interpreters that have one: what
 * the remote sends to the printer (printer controller mode: everything
 * until the sequence that ends it, not shown), the cursor's line as each
 * line feed leaves it (auto print), the screen or a line on request. What
 * would be printed goes to the listener's [TerminalEmulator.Listener.onPrint].
 */
internal class PrinterPort(
    private val t: TerminalEmulator,
    /** what ends controller mode (a VT102's CSI 4 i, a VT52's ESC X) */
    private val ends: List<String>,
) {
    /** printer controller mode: the remote's characters to the printer, not the screen */
    var controller = false
        private set
    /** auto print: each line printed as a line feed leaves it */
    var auto = false

    private val buf = StringBuilder()
    private val longest = ends.maxOf { it.length }

    /** controller mode begun */
    fun startController() {
        controller = true
        buf.setLength(0)
    }

    /** a character in controller mode: for the printer, until the sequence that ends the mode */
    fun feed(cp: Int) {
        buf.appendCp(cp)
        for (e in ends) {
            if (buf.endsWith(e)) {
                buf.setLength(buf.length - e.length)
                flush(buf.length)
                controller = false
                return
            }
        }
        // (a long job sent on in parts, all but what could be the start of an end)
        if (buf.length >= 4096) flush(buf.length - longest)
    }

    /** the first [n] characters held to the printer */
    private fun flush(n: Int) {
        if (n <= 0) return
        t.listener?.onPrint(buf.substring(0, n))
        buf.deleteRange(0, n)
    }

    /** the screen's line [y] printed */
    fun printLine(y: Int) {
        t.listener?.onPrint(t.line(y).text() + "\n")
    }

    /** the whole screen printed */
    fun printScreen() {
        val sb = StringBuilder()
        for (y in 0 until t.rows) sb.append(t.line(y).text()).append('\n')
        t.listener?.onPrint(sb.toString())
    }

    /** the port as at power-up: no controller or auto print, nothing held */
    fun reset() {
        controller = false
        auto = false
        buf.setLength(0)
    }
}
