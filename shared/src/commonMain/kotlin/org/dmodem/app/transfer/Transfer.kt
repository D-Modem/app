// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.IOException
import org.dmodem.app.io.background
import org.dmodem.app.io.monotonicMs
import org.dmodem.app.io.sleepMs
import kotlin.concurrent.Volatile

/** The file transfer protocols. */
enum class Protocol(val label: String, val batch: Boolean) {
    ZMODEM("ZMODEM", true),
    YMODEM("YMODEM", true),
    /** YMODEM with no ACKs: for a line that corrects its errors (a modem's V.42); an error ends it */
    YMODEM_G("YMODEM-G", true),
    XMODEM("XMODEM", false),
    XMODEM_1K("XMODEM-1K", false),
    KERMIT("Kermit", true),
    /** an image as Intel HEX records (an EPROM programmer's, a monitor's): no protocol but their checksums */
    INTEL_HEX("Intel HEX", false),
    /** the same as Motorola S-records */
    SREC("Motorola S-records", false),
    /** a file's bytes as they are, as if typed (received: what comes, until it goes quiet) */
    TEXT("Plain text", false),
    ;

    /** what to type at the remote's shell for it to send (the files' names after it), or to receive */
    fun remoteCommand(remoteSends: Boolean): String = when (this) {
        ZMODEM -> if (remoteSends) "sz -b " else "rz -b"
        YMODEM -> if (remoteSends) "sb " else "rb"
        // (sb sends YMODEM-G when asked with G; lrzsz's rb asks with C, and gets plain YMODEM)
        YMODEM_G -> if (remoteSends) "sb " else "rb"
        XMODEM -> if (remoteSends) "sx " else "rx -c "
        XMODEM_1K -> if (remoteSends) "sx -k " else "rx -c "
        KERMIT -> if (remoteSends) "kermit -i -s " else "kermit -i -r"
        // (a programmer or a monitor takes them as they come: nothing to type)
        INTEL_HEX, SREC -> ""
        // (sent: to whatever takes text, an editor, a BBS, cat > a file: nothing assumed)
        TEXT -> if (remoteSends) "cat " else ""
    }

    /** whether the file being received can be skipped (the protocol tells the sender) */
    val canSkip: Boolean get() = this == ZMODEM || this == KERMIT

    /** the hex records' format, for the two that are */
    val hexFormat: HexRecords.Format?
        get() = when (this) {
            INTEL_HEX -> HexRecords.Format.INTEL
            SREC -> HexRecords.Format.SREC
            else -> null
        }
}

/** A transfer's state as it goes, and how it ended. */
data class TransferState(
    val protocol: Protocol,
    /** true: files to the remote */
    val sending: Boolean,
    val progress: Progress = Progress(),
    /** null while it runs; then "" when it went well, else why it didn't */
    val result: String? = null,
    /** the names the received files got */
    val received: List<String> = emptyList(),
    /** when it started ([monotonicMs]), and when it ended (0 while it runs) */
    val startedMs: Long = 0,
    val endedMs: Long = 0,
    /** when the file now going started ([monotonicMs]) */
    val fileStartedMs: Long = 0,
) {
    val running: Boolean get() = result == null

    /** how long it has taken, at [now] ([monotonicMs]) */
    fun elapsedMs(now: Long = monotonicMs()): Long = if (startedMs == 0L) 0 else ((if (endedMs > 0) endedMs else now) - startedMs).coerceAtLeast(0)

    /** the file's bytes a second (its throughput), at [now]; 0 before a second has gone */
    fun cps(now: Long = monotonicMs()): Long {
        if (fileStartedMs == 0L) return 0
        val ms = (if (endedMs > 0) endedMs else now) - fileStartedMs
        return if (ms < 1000) 0 else progress.bytes * 1000 / ms
    }

    /** the time the file still takes at its [cps], at [now]; -1 not known */
    fun remainingMs(now: Long = monotonicMs()): Long {
        val c = cps(now)
        if (!running || c <= 0 || progress.size <= 0) return -1
        return (progress.size - progress.bytes).coerceAtLeast(0) * 1000 / c
    }

    /**
     * the times and the speed as HyperTerminal's progress shows them: "Elapsed 0:00:12,
     * Remaining 0:01:00, 2340 cps" ([bps]: bits a second, 10 to a character, as its cps/bps button gives)
     */
    fun timing(bps: Boolean): String = timing(bps, monotonicMs())

    /** [timing] at [now] ([monotonicMs]) */
    fun timing(bps: Boolean, now: Long): String {
        if (startedMs == 0L) return ""
        val parts = ArrayList<String>()
        parts.add("Elapsed " + clock(elapsedMs(now)))
        val left = remainingMs(now)
        if (left >= 0) parts.add("Remaining " + clock(left))
        val c = cps(now)
        if (c > 0) parts.add(if (bps) "${c * 10} bps" else "$c cps")
        return parts.joinToString(", ")
    }

    companion object {
        /** [ms] as HyperTerminal shows a time: 0:01:05 */
        fun clock(ms: Long): String {
            val s = ms.coerceAtLeast(0) / 1000
            return "${s / 3600}:" + "${s / 60 % 60}".padStart(2, '0') + ":" + "${s % 60}".padStart(2, '0')
        }
    }
}

/**
 * One transfer on a line, on its own thread (the Android app's terminal,
 * and iOS's TransferJob): the session feeds it the
 * remote's bytes ([io]) while it runs; [onEnd] has what it didn't read.
 */
class Transfer(
    val protocol: Protocol,
    val sending: Boolean,
    send: (ByteArray) -> Unit,
    /** the session's raw write (around a cancel, which [io] refuses) */
    private val sendRaw: (ByteArray) -> Unit,
    private val onState: (TransferState) -> Unit,
    private val onEnd: (TransferState, ByteArray) -> Unit,
) {
    /** ZMODEM with zstd where the other side can (docs/zmodem-zstd.md of D-Modem): set before it starts */
    var zstd = true

    val io = TransferIo(send)
    @Volatile var state = TransferState(protocol, sending)
        private set
    private var kermit: Kermit? = null

    /** the state now [s], and said so */
    private fun update(s: TransferState) {
        state = s
        onState(s)
    }

    private val progress: (Progress) -> Unit = { p ->
        // (a new file: its throughput from now)
        val fileStarted = if (p.file != state.progress.file || state.fileStartedMs == 0L) monotonicMs() else state.fileStartedMs
        update(state.copy(progress = p, fileStartedMs = fileStarted))
    }

    /** files received into [into] ([name]: XMODEM's, which sends none) */
    fun receive(into: Incoming, name: String = "received.bin") = start {
        when (protocol) {
            Protocol.ZMODEM -> ZModem(io, progress, zstd = zstd).receive(into)
            Protocol.YMODEM -> XYModem(io, progress).receiveY(into)
            Protocol.YMODEM_G -> XYModem(io, progress).receiveY(into, g = true)
            Protocol.XMODEM, Protocol.XMODEM_1K -> listOf(XYModem(io, progress).receiveX(into, name))
            Protocol.KERMIT -> Kermit(io, progress).also { kermit = it }.receive(into)
            Protocol.INTEL_HEX, Protocol.SREC -> HexTransfer(io, protocol.hexFormat!!, progress).receive(into, name)
            Protocol.TEXT -> TextTransfer(io, progress).receive(into, name)
        }
    }

    /**
     * [files] sent ([files]' first only, for XMODEM); plain text with a wait of [lineDelayMs]
     * after each line and [charDelayMs] after each character (HyperTerminal's ASCII Setup)
     */
    fun send(files: List<Outgoing>, lineDelayMs: Long = 0, charDelayMs: Long = 0) = start {
        when (protocol) {
            Protocol.ZMODEM -> ZModem(io, progress, zstd = zstd).send(files)
            Protocol.YMODEM -> XYModem(io, progress).sendY(files)
            Protocol.YMODEM_G -> XYModem(io, progress).sendY(files, g = true)
            Protocol.XMODEM -> XYModem(io, progress).sendX(files.first(), oneK = false)
            Protocol.XMODEM_1K -> XYModem(io, progress).sendX(files.first(), oneK = true)
            Protocol.KERMIT -> Kermit(io, progress).also { kermit = it }.send(files)
            Protocol.INTEL_HEX, Protocol.SREC -> HexTransfer(io, protocol.hexFormat!!, progress).send(files.first())
            Protocol.TEXT -> TextTransfer(io, progress).send(files.first(), lineDelayMs, charDelayMs)
        }
        emptyList()
    }

    /** [body] run on a thread of its own: its end (the files' names, or why it failed) the state's, then [onEnd] with what was left unread */
    private fun start(body: () -> List<String>) {
        update(state.copy(startedMs = monotonicMs()))
        background("transfer-${protocol.name.lowercase()}") {
            val end = try {
                val names = body()
                state.copy(result = "", received = names)
            } catch (e: TransferCancelled) {
                state.copy(result = e.message ?: "cancelled")
            } catch (e: IOException) {
                state.copy(result = e.message ?: e.toString())
            } catch (e: RuntimeException) {
                state.copy(result = e.toString())
            }
            // (what the other side still sends of the protocol settles first)
            if (end.result != "") sleepMs(300)
            val done = end.copy(endedMs = monotonicMs())
            update(done)
            onEnd(done, io.leftover())
        }
    }

    /** the file being received skipped, and the transfer on to the next (ZMODEM's ZSKIP, Kermit's X) */
    fun skip() {
        if (state.running && !sending && protocol.canSkip) io.requestSkip()
    }

    /** stopped: the other side told (CANs; Kermit's E packet), the thread ends at its next read */
    fun cancel() {
        if (!state.running) return
        runCatching {
            sendRaw(
                when (protocol) {
                    Protocol.KERMIT -> kermit?.cancelBytes() ?: ByteArray(0)
                    // (no protocol to tell: the records just stop)
                    Protocol.INTEL_HEX, Protocol.SREC, Protocol.TEXT -> ByteArray(0)
                    else -> ZModem.CANCEL
                },
            )
        }
        io.cancel("cancelled")
    }

    companion object {
        /**
         * where in [b] a remote starts a transfer of its own: sz (ZMODEM
         * receive here), rz (it waits for files: pick them), kermit -s
         */
        fun detect(b: ByteArray, len: Int): Pair<Int, Detected>? {
            val z = indexOf(b, len, ZModem.SZ_START)
            if (z >= 0) return z to Detected.ZMODEM_SEND
            val r = indexOf(b, len, ZModem.RZ_START)
            if (r >= 0) return r to Detected.ZMODEM_RECEIVE
            if (Kermit.looksLikeStart(b, 0, len)) {
                for (i in 0 until len) if (b[i].toInt() == Kermit.MARK) return i to Detected.KERMIT_SEND
            }
            return null
        }

        /** where [pat] first is in [b]'s first [len] bytes, or -1 */
        private fun indexOf(b: ByteArray, len: Int, pat: ByteArray): Int {
            outer@ for (i in 0..len - pat.size) {
                for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
                return i
            }
            return -1
        }
    }

    enum class Detected { ZMODEM_SEND, ZMODEM_RECEIVE, KERMIT_SEND }
}
