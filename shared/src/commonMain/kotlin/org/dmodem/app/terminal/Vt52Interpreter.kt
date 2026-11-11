// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The DEC VT52's escape sequences: one character after ESC (ESC Y and
 * two more for the cursor's place), no parameters, no attributes. A
 * VT52 profile has it as its interpreter; a VT100 and later run one in
 * their VT52 mode (DECANM reset), with their own graphics set, until ESC <
 * hands back to ANSI ([onAnsi]).
 */
open class Vt52Interpreter(
    t: TerminalEmulator,
    /** what ESC F switches to: the VT52's own set, or a VT100's DEC Special Graphics */
    private val graphicsSet: GSet,
    /** the answer to ESC Z: a VT52's ESC / K, a VT100's in VT52 mode ESC / Z */
    private val identity: String,
    /** ESC <: back to ANSI mode (null: a real VT52, which has none) */
    private val onAnsi: (() -> Unit)? = null,
) : TerminalInterpreter(t) {
    private enum class State { GROUND, ESC, ROW, COL, ARG }
    private var state = State.GROUND
    /** an ESC command's argument character to come, for [escArgument] (an Atari ST's colours) */
    private var argFor = 0
    private var graphics = false
    private var row = 0
    private val printer = PrinterPort(t, listOf("\u001bX"))

    init {
        t.vt52Keys = true
    }

    /** one code point: printed, a control, or part of an escape sequence */
    override fun process(cp: Int) {
        if (printer.controller) {
            printer.feed(cp)
            return
        }
        when (state) {
            State.GROUND -> when {
                cp == 0x1b -> state = State.ESC
                cp < 0x20 -> control(cp)
                cp == 0x7f -> {}
                else -> t.print(if (graphics) graphicsSet.map(cp) else charOf(cp))
            }
            State.ESC -> {
                state = State.GROUND
                esc(cp)
            }
            State.ROW -> {
                row = cp - 0x20
                state = State.COL
            }
            State.ARG -> {
                state = State.GROUND
                escArgument(argFor, cp)
            }
            State.COL -> {
                state = State.GROUND
                // (a row off the screen leaves the cursor's; a column past the end is the last)
                val y = if (row in 0 until t.rows) row else t.cursorY
                t.moveCursor(cp - 0x20, y)
            }
        }
    }

    /** what character [cp] prints as (an Atari ST's own set maps its octets) */
    protected open fun charOf(cp: Int): Int = cp

    /** a control: the bell, BS, HT, LF (auto printing), CR */
    private fun control(cp: Int) {
        when (cp) {
            0x07 -> t.listener?.onBell()
            0x08 -> t.backspace()
            0x09 -> t.tab(1)
            0x0a -> {
                if (printer.auto) printer.printLine(t.cursorY)
                t.index()
            }
            0x0d -> t.carriageReturn()
        }
    }

    /** the character after ESC: its command */
    protected open fun esc(cp: Int) {
        when (cp.toChar()) {
            'A' -> t.moveCursor(t.cursorX, t.cursorY - 1)
            'B' -> t.moveCursor(t.cursorX, t.cursorY + 1)
            'C' -> t.moveCursor(t.cursorX + 1, t.cursorY)
            'D' -> t.moveCursor(t.cursorX - 1, t.cursorY)
            'F' -> graphics = true
            'G' -> graphics = false
            'H' -> t.moveCursor(0, 0)
            'I' -> t.reverseIndex()
            'J' -> t.eraseDisplay(0)
            'K' -> t.eraseLine(0)
            'Y' -> state = State.ROW
            'Z' -> t.reply(identity)
            '=' -> t.appKeypad = true
            '>' -> t.appKeypad = false
            '<' -> onAnsi?.invoke()
            // hold screen on and off: output isn't held here
            '[', '\\' -> {}
            '^' -> printer.auto = true
            '_' -> printer.auto = false
            'W' -> printer.startController()
            'V' -> printer.printLine(t.cursorY)
            ']' -> printer.printScreen()
            else -> if (cp == 0x1b) state = State.ESC else if (cp < 0x20) control(cp)
        }
    }

    /** ESC [command]'s argument to come: the next character goes to [escArgument] */
    protected fun takeArgument(command: Int) {
        argFor = command
        state = State.ARG
    }

    /** the argument character [arg] of ESC [command] (one [takeArgument] asked for) */
    protected open fun escArgument(command: Int, arg: Int) {}

    /** RIS: graphics off, no sequence under way, the printer port as at power-up */
    override fun reset() {
        state = State.GROUND
        graphics = false
        printer.reset()
        t.vt52Keys = true
    }
}
