// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * What a home computer's screen editor was, as a terminal: octets read
 * as they come (the machine's own character set, not the user's), each
 * either one of its control codes or a character of its current set,
 * written in its colour and reverse. The machines (Commodore's PETSCII,
 * Atari's ATASCII) each give their codes and sets; what they share is
 * here: the screen's size and palette set on the emulator while it's
 * theirs, a wrap as soon as the last column is written (no deferred
 * wrap), cursor keys that wrap round the line ends, delete and insert
 * within the line, and typing turned back into the set's codes.
 */
abstract class ByteControlInterpreter(t: TerminalEmulator, private val machine: ByteMachine) : TerminalInterpreter(t) {
    /** the colour (an index of the machine's palette) the characters are written in */
    protected var color = machine.defaultColor
    /** the characters written reversed */
    protected var reverse = false
    /** the attributes the machine adds (a C128's underline and flash) */
    protected var extraAttrs = 0

    init {
        attach()
    }

    override val readsOctets: Boolean get() = true

    /** the machine's size and palette on the emulator */
    private fun attach() {
        t.useMachine(machine)
        t.autowrap = true
        t.style = look()
    }

    /** the view's size and the theme's colours back, for the profile taking over */
    override fun detach() {
        t.releaseMachine()
    }

    /** the machine as it starts: its colour, no reverse, the tab stops, its screen and palette */
    override fun reset() {
        color = machine.defaultColor
        reverse = false
        extraAttrs = 0
        resetTabs()
        attach()
    }

    /** reverse and the machine's attributes off */
    override fun softReset() {
        reverse = false
        extraAttrs = 0
        t.style = look()
    }

    /** an octet: one of the machine's control codes, else a character of its set */
    override fun process(cp: Int) {
        if (control(cp)) return
        val c = charOf(cp)
        if (c != 0) put(c)
    }

    /** octet [b] as one of the machine's control codes: true if it was one (and done) */
    protected abstract fun control(b: Int): Boolean

    /** octet [b] as a character of the current set (its code point), 0 for none */
    protected abstract fun charOf(b: Int): Int

    /** what's written now looks like: the colour, reverse, the machine's attributes */
    protected open fun look(): Long =
        Style.of(color, Style.DEFAULT_BG, (if (reverse) Style.INVERSE else 0) or extraAttrs)

    /** the colour written in from now on */
    protected fun colorTo(c: Int) {
        color = c
        t.style = look()
    }

    /** reverse on or off from now on */
    protected fun reverseTo(on: Boolean) {
        reverse = on
        t.style = look()
    }

    /** [a] on or off among the machine's attributes from now on */
    protected fun attrTo(a: Int, on: Boolean) {
        extraAttrs = if (on) extraAttrs or a else extraAttrs and a.inv()
        t.style = look()
    }

    /** character [cp] written at the cursor, the cursor to the next cell (past the last column: the next line's start) */
    protected fun put(cp: Int) {
        t.style = look()
        t.print(cp)
        if (t.pendingWrap) newLine(wrapped = true)
    }

    /** the cursor to the next line's start, the screen scrolled at the bottom ([wrapped]: the line runs on into it) */
    protected fun newLine(wrapped: Boolean = false) {
        if (wrapped) t.line(t.cursorY).wrapped = true
        t.carriageReturn()
        t.index()
    }

    /** the cursor up a line (at the top: stays) */
    protected fun up() {
        t.moveCursor(t.cursorX, t.cursorY - 1)
    }

    /** the cursor down a line (at the bottom: the screen scrolls) */
    protected fun down() {
        val x = t.cursorX
        t.index()
        t.moveCursor(x, t.cursorY)
    }

    /** the cursor left (at a line's start: the end of the one above, at home it stays) */
    protected fun left() {
        when {
            t.cursorX > 0 -> t.moveCursor(t.cursorX - 1, t.cursorY)
            t.cursorY > 0 -> t.moveCursor(t.cols - 1, t.cursorY - 1)
        }
    }

    /** the cursor right (at a line's end: the next one's start, scrolling at the bottom) */
    protected fun right() {
        if (t.cursorX < t.cols - 1) t.moveCursor(t.cursorX + 1, t.cursorY) else newLine()
    }

    /** the cursor home, top left */
    protected fun home() {
        t.moveCursor(0, 0)
    }

    /** the screen cleared, the cursor home */
    protected fun clear() {
        t.style = Style.erased(look())
        t.eraseDisplay(2)
        home()
        t.style = look()
    }

    /** the character left of the cursor deleted, the rest of the line pulled left (at a line's start: the one above's last) */
    protected fun deleteBack() {
        if (t.cursorX == 0 && t.cursorY == 0) return
        left()
        t.style = Style.erased(look())
        t.deleteChars(1)
        t.style = look()
    }

    /** a blank at the cursor, the rest of the line pushed right */
    protected fun insertBlank() {
        t.style = Style.erased(look())
        t.insertChars(1)
        t.style = look()
    }

    /** the tab stops (every eighth column to begin with) */
    protected val tabs = BooleanArray(256) { it % 8 == 0 && it > 0 }

    /** the cursor to the next tab stop (the line's last column if none) */
    protected fun tab() {
        var x = t.cursorX + 1
        while (x < t.cols - 1 && !tabs[x]) x++
        t.moveCursor(x.coerceAtMost(t.cols - 1), t.cursorY)
    }

    /** a tab stop at the cursor's column set or cleared */
    protected fun tabStop(on: Boolean) {
        if (t.cursorX in tabs.indices) tabs[t.cursorX] = on
    }

    /** the tab stops back to every eighth column ([every]: true), or all cleared */
    protected fun resetTabs(every: Boolean = true) {
        for (i in tabs.indices) tabs[i] = every && i % 8 == 0 && i > 0
    }

    /** the bell */
    protected fun bell() {
        t.listener?.onBell()
    }

    /** code point [cp] as the first code in [codes] of set [set] (256 code points, 0 for none) that has it; null if none does */
    protected fun codeIn(set: IntArray, cp: Int, codes: IntRange = set.indices): Int? {
        for (b in codes) if (set[b] == cp) return b
        return null
    }
}

/** [m]'s screen size and palette (its default colours the screen's own) on the emulator */
internal fun TerminalEmulator.useMachine(m: ByteMachine) {
    requestedColumns = m.columns
    requestedRows = m.rows
    val p = m.palette
    for (i in p.indices) setColorOverride(i, p[i])
    setColorOverride(256, p[m.defaultColor])
    setColorOverride(257, p[m.background])
}

/** the view's size and the theme's colours back after [useMachine], the style plain */
internal fun TerminalEmulator.releaseMachine() {
    requestedColumns = 0
    requestedRows = 0
    resetColors(0..257)
    style = Style.NORMAL
}

/**
 * The machine a [ByteControlInterpreter] is: its screen's size and its
 * palette (ARGB, in its own colour order), the colour it writes in and
 * the background at the start.
 */
interface ByteMachine {
    val columns: Int
    val rows: Int
    val palette: IntArray
    val defaultColor: Int
    val background: Int
}
