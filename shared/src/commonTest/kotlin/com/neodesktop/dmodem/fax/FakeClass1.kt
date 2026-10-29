// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.ByteQueue
import com.neodesktop.dmodem.io.Bytes
import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.sleepMs
import com.neodesktop.dmodem.transfer.TransferIo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * Two class 1 modems in memory with a line between them, for T.30's
 * tests in common Kotlin: what one transmits (V.21 frames, +FTH; data
 * on a fast modulation, +FTM) the other receives in turn (+FRH, +FRM),
 * at once and without loss, but for what [damage] does to the fast
 * data. A fast transmission received at another modulation than it was
 * sent with fails (ERROR): the long training and the short one told
 * apart, as a real modem's receiver would.
 */
class FakeFaxLine {
    sealed class Tx {
        class V21(val frames: List<ByteArray>) : Tx()
        class Fast(val mod: Int, val data: ByteArray) : Tx()
    }

    /** the fast data [from] a modem sends (its [n]th transmission there), as the other one gets it */
    @Volatile var damage: (from: String, n: Int, data: ByteArray) -> ByteArray = { _, _, d -> d }

    /** the FCFs of the frames each way, in order ("A>" + hex), for the tests to look at */
    val seen = ArrayList<String>()
    private val lock = SynchronizedObject()

    /** the FCFs seen so far, a copy */
    fun seen(): List<String> = synchronized(lock) { seen.toList() }

    inner class Modem(val name: String) {
        /** the modem's DTE: our Class1 on it */
        val io = TransferIo { b -> fromDte.put(b) }
        val c1 = Class1(io)
        lateinit var other: Modem
        private val fromDte = ByteQueue()
        private var cur = ByteArray(0)
        private var at = 0
        private val inbox = ArrayList<Tx>()
        private var fastSent = 0
        /** the V.21 frames being received, those left of them */
        private var receiving: ArrayDeque<ByteArray>? = null
        @Volatile var stopped = false
        /** what the modem says, to here instead of [io] (a FaxJob's feed) */
        @Volatile var tap: ((ByteArray) -> Unit)? = null

        /** what the modem says, to [tap] if set, else to its DTE */
        private fun toHost(b: ByteArray) {
            val t = tap
            if (t != null) t(b) else io.feed(b)
        }

        /** a transmission from the other modem, kept for this one's receiver */
        fun deliver(t: Tx) = synchronized(lock) { inbox.add(t) }

        /** whether the DTE has sent something not yet read */
        private fun dteWaiting() = at < cur.size || fromDte.isNotEmpty()

        /** the DTE's next octet (waited for); an error once stopped */
        private fun byte(): Int {
            while (at >= cur.size) {
                if (stopped) throw IllegalStateException("stopped")
                cur = fromDte.poll(50) ?: continue
                at = 0
            }
            return cur[at++].toInt() and 0xff
        }

        /** [s] said to the DTE as a result line */
        private fun say(s: String) = toHost("\r\n$s\r\n".encodeToByteArray())

        /** <DLE> shielded data from the DTE, to <DLE><ETX> */
        private fun dteData(): ByteArray {
            val o = Bytes()
            while (true) {
                val c = byte()
                if (c != Class1.DLE) { o.write(c); continue }
                when (byte()) {
                    Class1.ETX -> return o.toByteArray()
                    Class1.DLE -> o.write(Class1.DLE)
                }
            }
        }

        /** [d] to the DTE <DLE> shielded, <DLE><ETX> after */
        private fun toDte(d: ByteArray) {
            val o = Bytes()
            for (b in d) { o.write(b.toInt()); if (b.toInt() and 0xff == Class1.DLE) o.write(Class1.DLE) }
            o.write(Class1.DLE)
            o.write(Class1.ETX)
            toHost(o.toByteArray())
        }

        /** the next transmission come in (left there); null if the DTE stopped the wait (a character) */
        private fun waitTx(): Tx? {
            while (true) {
                synchronized(lock) { inbox.firstOrNull() }?.let { return it }
                if (dteWaiting()) {
                    byte()
                    return null
                }
                if (stopped) throw IllegalStateException("stopped")
                sleepMs(2)
            }
        }

        /** the transmission come in first, taken */
        private fun take() = synchronized(lock) { inbox.removeAt(0) }

        /** +FTH=3 from its CONNECT: frames until the final one (or an empty one) */
        private fun sendFrames() {
            val frames = ArrayList<ByteArray>()
            while (true) {
                val f = dteData()
                if (f.isEmpty()) break
                frames.add(f)
                synchronized(lock) { seen.add("$name>" + hex2(f.getOrElse(2) { 0 }.toInt() and 0xff)) }
                if (f.size >= 2 && f[1].toInt() and 0x10 != 0) break
                say("CONNECT")
            }
            other.deliver(Tx.V21(frames))
            say("OK")
        }

        /** +FRH=3: the next frame (of those coming, else of the next V.21 transmission) */
        private fun receiveFrame(connected: Boolean) {
            while (receiving.isNullOrEmpty()) {
                val t = waitTx() ?: run { say("OK"); return }
                take()
                // (fast data while V.21 is listened for: lost)
                if (t is Tx.V21) receiving = ArrayDeque(t.frames)
            }
            if (!connected) say("CONNECT")
            // (its FCS: two octets, as a modem passes them)
            toDte(receiving!!.removeFirst() + byteArrayOf(0, 0))
            say("OK")
        }

        /** +FRM=<mod> */
        private fun receiveFast(mod: Int) {
            receiving = null
            val t = waitTx() ?: run { say("OK"); return }
            if (t is Tx.V21) { say("+FCERROR"); return }
            take()
            t as Tx.Fast
            if (t.mod != mod) { say("ERROR"); return }
            say("CONNECT")
            toDte(t.data)
            say("NO CARRIER")
        }

        /** an AT command from the DTE carried out: answered, and what it sends or receives done */
        private fun command(cmd: String) {
            val c = cmd.uppercase().removePrefix("AT")
            when {
                c == "+FTM=?" -> { say("24,48,72,73,74,96,97,98,121,122,145,146"); say("OK") }
                c == "+FTH=3" -> { say("CONNECT"); sendFrames() }
                c == "+FRH=3" -> receiveFrame(false)
                c.startsWith("+FTM=") -> {
                    val mod = c.substring(5).toInt()
                    say("CONNECT")
                    var d = dteData()
                    if (d.isNotEmpty() && d[0].toInt() and 0xff == 0x7e) d = damage(name, fastSent, d)
                    fastSent++
                    other.deliver(Tx.Fast(mod, d))
                    say("OK")
                }
                c.startsWith("+FRM=") -> receiveFast(c.substring(5).toInt())
                // dialled: CONNECT, then as +FRH=3; answered: CONNECT, then as +FTH=3
                c.startsWith("D") -> { say("CONNECT"); receiveFrame(true) }
                c == "A" -> { say("CONNECT"); sendFrames() }
                else -> say("OK")      // (E0, +FCLASS=1, +FTS, +FRS, …)
            }
        }

        /** the modem's thread: the DTE's command lines read and carried out until it stops */
        fun start() = background("fake modem $name") {
            val line = StringBuilder()
            try {
                while (!stopped) {
                    val b = byte()
                    if (b == '\r'.code) {
                        if (line.isNotEmpty()) command(line.toString())
                        line.clear()
                    } else if (b >= ' '.code) {
                        line.append(b.toChar())
                    }
                }
            } catch (_: IllegalStateException) {
            }
        }
    }

    val a = Modem("A")
    val b = Modem("B")

    init {
        a.other = b
        b.other = a
        a.start()
        b.start()
    }

    /** both modems stopped */
    fun stop() {
        a.stopped = true
        b.stopped = true
    }
}
