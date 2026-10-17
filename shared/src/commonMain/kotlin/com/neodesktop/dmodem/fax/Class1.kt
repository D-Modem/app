// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.*

import com.neodesktop.dmodem.transfer.TransferIo

/**
 * A class 1 fax modem (T.31) from its DTE: AT commands and their
 * results; HDLC frames (V.21, +FTH=3/+FRH=3) and fast data (+FTM/+FRM)
 * as <DLE> shielded octets ending in <DLE><ETX>.
 */
class Class1(private val io: TransferIo, private val log: (String) -> Unit = {}) {

    /** a frame received: address, control, FCF and its FIF (the FCS off); [ok]: its FCS was good */
    class Frame(val bytes: ByteArray, val ok: Boolean) {
        val fcf: Int get() = if (bytes.size >= 3) bytes[2].toInt() and 0xff else -1
        val final: Boolean get() = bytes.size >= 2 && (bytes[1].toInt() and 0x10) != 0
        val fif: ByteArray get() = if (bytes.size > 3) bytes.copyOfRange(3, bytes.size) else ByteArray(0)
    }

    /** a line from the modem, its CR LF off; null when none came in [ms] */
    fun line(ms: Long): String? {
        val sb = StringBuilder()
        val end = monotonicMs() + ms
        while (true) {
            val left = end - monotonicMs()
            if (left <= 0) return null
            val c = io.read(left)
            if (c < 0) return null
            when (c) {
                '\n'.code -> if (sb.isNotEmpty()) return sb.toString().also { log("<- $it") }
                '\r'.code -> {}
                else -> sb.append(c.toChar())
            }
        }
    }

    /** a command (without its AT): its result line (OK, CONNECT, ERROR, NO CARRIER, +FCERROR, …), null on a timeout */
    fun command(cmd: String, ms: Long = 5000): String? {
        log("-> AT$cmd")
        io.write("AT$cmd\r".encodeToByteArray())
        return result(ms)
    }

    /** a command out (without its AT), its result left for [result] */
    fun send(cmd: String) {
        log("-> AT$cmd")
        io.write("AT$cmd\r".encodeToByteArray())
    }

    /** the next result line (the command's echo, and anything else that isn't one, passed over) */
    fun result(ms: Long): String? {
        val end = monotonicMs() + ms
        while (true) {
            val l = line(end - monotonicMs()) ?: return null
            if (l.startsWith("AT", ignoreCase = true)) continue
            if (l in RESULTS || l.startsWith("CONNECT") || l.startsWith("+F") && !l.startsWith("+FTM:")) return l
            lastInfo = l
        }
    }

    /** the last line that was not a result (AT+FTM=?'s list, say) */
    var lastInfo: String? = null
        private set

    /** octets out, <DLE> doubled, <DLE><ETX> after */
    fun sendData(data: ByteArray, end: Boolean = true) {
        val o = Bytes(data.size + data.size / 64 + 4)
        for (b in data) {
            o.write(b.toInt())
            if (b == DLE.toByte()) o.write(DLE)
        }
        if (end) { o.write(DLE); o.write(ETX) }
        io.write(o.toByteArray())
    }

    /**
     * <DLE> shielded octets in until <DLE><ETX>: the octets; null on a
     * timeout of [ms] with nothing coming
     */
    fun readData(ms: Long, max: Int = 4 * 1024 * 1024): ByteArray? {
        val o = Bytes()
        var dle = false
        while (true) {
            val c = io.read(ms)
            if (c < 0) return null
            if (dle) {
                dle = false
                when (c) {
                    ETX -> return o.toByteArray()
                    DLE -> o.write(DLE)
                    // (<DLE><SUB>: two <DLE>s, T.31's; others dropped)
                    SUB -> { o.write(DLE); o.write(DLE) }
                }
            } else if (c == DLE) {
                dle = true
            } else {
                o.write(c)
            }
            if (o.size > max) throw IOException("too much data")
        }
    }

    /** frames out (+FTH=3 given, its CONNECT seen, or after ATA): each in turn, the last final; true when the modem took them */
    fun sendFrames(frames: List<ByteArray>, ms: Long = 10_000): Boolean {
        for ((i, f) in frames.withIndex()) {
            val last = i == frames.lastIndex
            val b = f.copyOf()
            b[1] = (if (last) CTRL_FINAL else CTRL) .toByte()
            log("-> frame ${hex(b)}")
            sendData(b)
            val r = result(ms)
            if (r != (if (last) "OK" else "CONNECT")) {
                log("frame not sent: $r")
                return false
            }
        }
        return true
    }

    /** +FTH=3 and the frames */
    fun transmitFrames(frames: List<ByteArray>): Boolean {
        val r = command("+FTH=3", 10_000)
        if (r != "CONNECT") return false
        return sendFrames(frames)
    }

    /**
     * a frame in (+FRH=3 given and its CONNECT seen, or after ATD): its
     * octets and whether OK followed; null when the data didn't come
     */
    fun readFrame(ms: Long = 10_000): Frame? {
        val d = readData(ms) ?: return null
        val r = result(3000)
        val ok = r == "OK"
        // (the two FCS octets the modem passes with it)
        val body = if (d.size >= 2) d.copyOf(d.size - 2) else d
        log("<- frame ${hex(body)} ${if (ok) "" else "(bad: $r)"}")
        return Frame(body, ok)
    }

    /** +FRH=3 and the frame it gets: null for none (NO CARRIER, +FCERROR, a timeout: the command stopped) */
    fun receiveFrame(ms: Long = T2_MS): Frame? {
        val r = command("+FRH=3", ms)
        if (r == null) {
            abort()
            return null
        }
        if (r != "CONNECT") return null
        return readFrame()
    }

    /** a command waiting (+FRH, +FRM) stopped: any character, then its OK */
    fun abort() {
        io.write(byteArrayOf(CAN.toByte()))
        result(2000)
    }

    companion object {
        const val DLE = 0x10
        const val ETX = 0x03
        const val SUB = 0x1a
        const val CAN = 0x18
        const val ADDR = 0xff
        const val CTRL = 0x03
        const val CTRL_FINAL = 0x13
        const val T2_MS = 6000L
        val RESULTS = setOf("OK", "ERROR", "NO CARRIER", "NO DIALTONE", "NO DIAL TONE", "BUSY", "NO ANSWER", "RING", "FCERROR")

        fun hex(b: ByteArray) = b.joinToString(" ") { hex2(it.toInt() and 0xff) }

        /** a frame: address, control (set when sent), FCF, FIF */
        fun frame(fcf: Int, fif: ByteArray = ByteArray(0)) = byteArrayOf(ADDR.toByte(), CTRL.toByte(), fcf.toByte()) + fif
    }
}
