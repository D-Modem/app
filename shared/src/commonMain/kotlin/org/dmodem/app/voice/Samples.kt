// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** a voice call's samples: 16 bit, 8000 a second, one channel */
const val VOICE_RATE = 8000

/** A ring of samples that keeps the newest [cap] of them, for two threads (the call's, the sound card's). */
class SampleRing(private val cap: Int) {
    private val lock = SynchronizedObject()
    private val p = ShortArray(cap)
    private var head = 0
    private var n = 0

    /** how many are in it */
    val size: Int get() = synchronized(lock) { n }

    /** emptied */
    fun clear() = synchronized(lock) { head = 0; n = 0 }

    /** [len] of [x] from [off] put in: the oldest dropped when it's full */
    fun put(x: ShortArray, off: Int = 0, len: Int = x.size - off) = synchronized(lock) {
        for (i in 0 until len) {
            p[(head + n) % cap] = x[off + i]
            if (n < cap) n++ else head = (head + 1) % cap
        }
    }

    /** the one sample [x] put in */
    fun put(x: Int) = synchronized(lock) {
        p[(head + n) % cap] = x.toShort()
        if (n < cap) n++ else head = (head + 1) % cap
    }

    /** up to [len] taken out into [out] at [off]: how many */
    fun get(out: ShortArray, off: Int = 0, len: Int = out.size - off): Int = synchronized(lock) {
        val k = minOf(n, len)
        for (i in 0 until k) {
            out[off + i] = p[head]
            head = (head + 1) % cap
        }
        n -= k
        k
    }

    /** the next sample, or null when it's empty */
    fun take(): Int? = synchronized(lock) {
        if (n == 0) return@synchronized null
        val x = p[head].toInt()
        head = (head + 1) % cap
        n--
        x
    }

    /** the oldest taken out until [keep] are left */
    fun keep(keep: Int) = synchronized(lock) {
        if (n > keep) {
            head = (head + n - keep) % cap
            n = keep
        }
    }
}

/** A tone of one or two frequencies (a DTMF digit, the answering machine's beep), [samples] long. */
class Tone(f1: Double, f2: Double, samples: Int, private val amp: Double) {
    private val k1 = 2 * PI * f1 / VOICE_RATE
    private val k2 = 2 * PI * f2 / VOICE_RATE
    private val two = f2 > 0
    private var c1 = 1.0
    private var s1 = 0.0
    private var c2 = 1.0
    private var s2 = 0.0

    /** samples still to come */
    var left = samples
        private set

    /** its next sample (0 once it has ended) */
    fun next(): Int {
        if (left <= 0) return 0
        left--
        var y = turn1()
        if (two) y = (y + turn2()) / 2
        return (y * amp).toInt()
    }

    // (each oscillator a unit vector turned by its step each sample, kept on the circle)
    private fun turn1(): Double {
        val c0 = c1
        val s0 = s1
        c1 = c0 * cos(k1) - s0 * sin(k1)
        s1 = c0 * sin(k1) + s0 * cos(k1)
        val m = sqrt(c1 * c1 + s1 * s1)
        if (m > 0) { c1 /= m; s1 /= m }
        return s0
    }

    /** the second oscillator turned a step: its last sine */
    private fun turn2(): Double {
        val c0 = c2
        val s0 = s2
        c2 = c0 * cos(k2) - s0 * sin(k2)
        s2 = c0 * sin(k2) + s0 * cos(k2)
        val m = sqrt(c2 * c2 + s2 * s2)
        if (m > 0) { c2 /= m; s2 /= m }
        return s0
    }

    companion object {
        private const val KEYS = "123A456B789C*0#D"
        private val ROW = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
        private val COL = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)

        /** DTMF digit [d]'s two frequencies, or null if it isn't one */
        fun dtmf(d: Char): Pair<Double, Double>? {
            val k = KEYS.indexOf(d.uppercaseChar())
            return if (k < 0) null else ROW[k / 4] to COL[k % 4]
        }
    }
}
