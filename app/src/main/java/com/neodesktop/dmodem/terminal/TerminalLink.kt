// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * What a terminal session runs over: the modem's DTE, an SSH channel, a
 * Telnet connection. Its octets go to every listener; anything can write
 * to it.
 */
interface TerminalLink {

    fun interface Listener {
        /** octets from the far end: [data] up to [length], valid only during the call */
        fun onData(data: ByteArray, length: Int)
    }

    sealed class State {
        object Connecting : State()
        object Open : State()
        /** closed: why, or null when it was asked to */
        data class Closed(val reason: String?) : State()
    }

    val state: StateFlow<State>

    /** [l] told the octets from the far end from now on */
    fun addListener(l: Listener)
    /** [l] told no more */
    fun removeListener(l: Listener)

    /** octets to the far end: false if it can't take them (closed) */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean

    /** the terminal's new size (SSH's window change, Telnet's NAWS) */
    fun resize(cols: Int, rows: Int) {}

    /** the link closed (for good) */
    fun close() {}
}
