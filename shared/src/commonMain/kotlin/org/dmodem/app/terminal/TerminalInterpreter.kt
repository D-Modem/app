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

    /** whether it reads the remote's octets as they come (its own character set), not code points decoded in the user's */
    open val readsOctets: Boolean get() = false

    /** what key [k] sends on this terminal, as octets; null: the VT sequences ([KeyEncoder]) */
    open fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? = null

    /** what character [cp] typed or pasted sends on this terminal, as octets; null: [KeyEncoder]'s, in the character set */
    open fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? = null

    /** another profile's interpreter taking over: what this one set on the screen (its size, its colours) undone */
    open fun detach() {}
}
