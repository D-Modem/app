// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.jobs

import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.latin1String
import com.neodesktop.dmodem.transfer.TransferIo
import kotlin.concurrent.Volatile

/*
 * The modem's DTE lent to a job (a fax call, a file transfer, dial-up
 * internet) as the iOS app's Swift has it: octets cross as Latin-1
 * strings (one character an octet), the job calls back through the
 * interfaces here (Swift classes conform to them; a Kotlin lambda's Unit
 * would be Swift's KotlinUnit), its state read when it says it changed.
 */

/** octets to the modem: false once it has gone */
fun interface DteOut {
    /** [latin1]'s octets to the modem: false once it has gone */
    fun write(latin1: String): Boolean
}

/** a line for the log */
fun interface LogOut {
    /** [line] logged */
    fun log(line: String)
}

/** the job's state changed (read it); on the job's thread */
fun interface Changed {
    /** the job's state changed */
    fun changed()
}

/** octets as Latin-1 text and back, for Swift */
object Latin1 {
    /** [s]'s characters as octets, one each */
    fun bytes(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }
    /** [b]'s octets as characters, one each */
    fun string(b: ByteArray): String = latin1String(b, 0, b.size)
}

/**
 * A job on the DTE: the modem's octets [feed] to it while it runs, its
 * own to [out] ([io]'s writes, refused once it's cancelled; [raw]'s, not:
 * the hang-up after a cancel). Ended, [leftover] has what came that it
 * didn't read, for the terminal.
 */
abstract class DteJob(private val out: DteOut, protected val logOut: LogOut, private val changed: Changed) {
    val io = TransferIo { b -> if (!out.write(Latin1.string(b))) throw IOException("the modem has gone") }

    /** what came but wasn't read, once it has ended */
    @Volatile var leftover: String = ""
        protected set

    /** the modem's octets */
    open fun feed(latin1: String) {
        if (latin1.isNotEmpty()) io.feed(Latin1.bytes(latin1))
    }

    /** to the modem, a cancel or not */
    protected fun raw(s: String) {
        try {
            out.write(s)
        } catch (_: Exception) {
        }
    }

    /** the job's state changed, said to [changed] */
    protected fun changed() = changed.changed()

    /** [line] to the log */
    protected fun log(line: String) = logOut.log(line)

    /** the modem's answer to ATD, as the user reads it */
    protected fun dialResult(r: String?, nobody: String) = when (r) {
        null -> "no answer"
        "BUSY" -> "busy"
        "NO ANSWER" -> "no answer"
        "NO CARRIER" -> nobody
        "NO DIALTONE", "NO DIAL TONE" -> "no line"
        else -> r.lowercase()
    }
}
