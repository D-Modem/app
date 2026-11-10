// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * What reads the remote's characters for a [TerminalEmulator]: the
 * sequences of one family of terminals (DEC's VT line and xterm; later
 * Commodore's PETSCII, Atari's ATASCII, videotex), carried out on the
 * emulator's screen through its shared operations (print, scroll, erase,
 * move the cursor). The screen, the scrollback, selection and drawing are
 * the same for all of them; only this part differs.
 */
abstract class TerminalInterpreter(protected val t: TerminalEmulator) {
    /** one code point from the remote, decoded */
    abstract fun process(cp: Int)

    /** RIS: the interpreter's own state as at the start */
    open fun reset() {}

    /** a soft reset (DECSTR): the interpreter's modes and character sets back to their defaults */
    open fun softReset() {}

    /** the character sets in use, for the cursor's save (DECSC); null if it has none */
    open fun charsetState(): Any? = null

    /** the character sets saved with the cursor back ([charsetState]'s, or null: the defaults) */
    open fun restoreCharsetState(s: Any?) {}
}
