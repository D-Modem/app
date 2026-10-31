// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.jobs

import org.dmodem.app.transfer.Incoming
import org.dmodem.app.transfer.Outgoing
import org.dmodem.app.transfer.Protocol
import org.dmodem.app.transfer.Transfer
import org.dmodem.app.transfer.TransferState
import kotlin.concurrent.Volatile

/**
 * A file transfer on the modem's line (the Android app's terminal's, for
 * iOS): files sent to the remote, or received from it, by [protocolName]'s
 * name (ZMODEM, YMODEM, XMODEM, XMODEM_1K, KERMIT).
 */
class TransferJob(protocolName: String, sending: Boolean, out: DteOut, log: LogOut, changed: Changed) : DteJob(out, log, changed) {
    private val transfer = Transfer(
        Protocol.valueOf(protocolName),
        sending,
        send = { b -> io.write(b) },
        sendRaw = { b -> raw(Latin1.string(b)) },
        onState = { s ->
            state = s
            changed()
        },
        onEnd = { s, rest ->
            leftover = Latin1.string(rest)
            state = s
            changed()
        },
    )

    @Volatile var state: TransferState = transfer.state
        private set

    /** the modem's octets, to the transfer */
    override fun feed(latin1: String) {
        if (latin1.isNotEmpty()) transfer.io.feed(Latin1.bytes(latin1))
    }

    /** files received into [into] ([name]: XMODEM's, which sends none) */
    fun receive(into: Incoming, name: String) = transfer.receive(into, name)

    /** [files] sent ([files]' first only, for XMODEM) */
    fun send(files: List<Outgoing>) = transfer.send(files)

    /** the transfer stopped */
    fun cancel() = transfer.cancel()

    companion object {
        /** the protocols' names (what each is called: [label]) */
        val protocols: List<String> = Protocol.entries.map { it.name }

        /** what the protocol [protocolName] is called */
        fun label(protocolName: String): String = Protocol.valueOf(protocolName).label

        /** whether it sends more than one file */
        fun batch(protocolName: String): Boolean = Protocol.valueOf(protocolName).batch

        /**
         * what the remote starts in what came ([latin1]): "ZMODEM_SEND" (sz: receive here),
         * "ZMODEM_RECEIVE" (rz: pick files), "KERMIT_SEND", or "" for nothing
         */
        fun detect(latin1: String): String {
            val b = Latin1.bytes(latin1)
            return Transfer.detect(b, b.size)?.second?.name ?: ""
        }
    }
}
