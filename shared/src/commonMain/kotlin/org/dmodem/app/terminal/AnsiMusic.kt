// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.pow
import kotlin.math.roundToInt

/** A note to play: its pitch ([hz], 0 for a rest), how long it sounds and the silence after it. */
data class MusicNote(val hz: Double, val ms: Int, val restMs: Int = 0)

/**
 * ANSI music (BananaCom's, as SyncTERM plays it): a BASIC PLAY string
 * after the CSI that starts it, to the SO (0E) that ends it. Notes A-G
 * (with # or + sharp, - flat, a length and dots), N for a note by number,
 * P for a pause, O, < and > for the octave, L for the length, T for the
 * tempo, MN, ML and MS for how much of a note sounds (MF and MB: played
 * now or behind, as the app always plays it: behind). Tempo, octave,
 * length and articulation carry from one string to the next, as PLAY's did.
 */
class AnsiMusic {
    /** quarter notes a minute */
    var tempo = 120
        private set
    var octave = 4
        private set
    /** the notes' length: 4 a quarter */
    var length = 4
        private set
    /** the part of a note that sounds: 7/8 normal, all legato, 3/4 staccato */
    private var sounding = 7.0 / 8

    /** the state back to PLAY's at the start */
    fun reset() {
        tempo = 120
        octave = 4
        length = 4
        sounding = 7.0 / 8
    }

    /** [s] as notes */
    fun parse(s: String): List<MusicNote> {
        val out = ArrayList<MusicNote>()
        val u = s.uppercase()
        var i = 0

        /** a number at i, or null (i past it) */
        fun number(): Int? {
            val start = i
            while (i < u.length && u[i].isDigit()) i++
            return if (i > start) u.substring(start, i).toIntOrNull() else null
        }

        /** the dots at i, as the factor they lengthen by */
        fun dots(): Double {
            var f = 1.0
            var add = 0.5
            while (i < u.length && u[i] == '.') {
                f += add
                add /= 2
                i++
            }
            return f
        }

        /** a note of PLAY's numbering (0: octave 0's C), [len] long, [dot] its dots' factor */
        fun note(n: Int, len: Int, dot: Double) {
            val ms = noteMs(len, dot)
            val on = (ms * sounding).roundToInt()
            out.add(MusicNote(frequency(n), on, ms - on))
        }

        while (i < u.length) {
            val c = u[i++]
            when (c) {
                in 'A'..'G' -> {
                    var n = octave * 12 + SEMITONES[c - 'A']
                    if (i < u.length && (u[i] == '#' || u[i] == '+')) { n++; i++ } else if (i < u.length && u[i] == '-') { n--; i++ }
                    val len = number()?.takeIf { it in 1..64 } ?: length
                    note(n.coerceIn(0, 83), len, dots())
                }
                'N' -> {
                    val n = number() ?: continue
                    val dot = dots()
                    if (n == 0) out.add(MusicNote(0.0, 0, noteMs(length, dot))) else if (n in 1..84) note(n - 1, length, dot)
                }
                'P' -> {
                    val len = number()?.takeIf { it in 1..64 } ?: length
                    out.add(MusicNote(0.0, 0, noteMs(len, dots())))
                }
                'O' -> number()?.let { octave = it.coerceIn(0, 6) }
                '>' -> octave = (octave + 1).coerceAtMost(6)
                '<' -> octave = (octave - 1).coerceAtLeast(0)
                'L' -> number()?.let { if (it in 1..64) length = it }
                'T' -> number()?.let { if (it in 32..255) tempo = it }
                'M' -> if (i < u.length) {
                    when (u[i]) {
                        'N' -> sounding = 7.0 / 8
                        'L' -> sounding = 1.0
                        'S' -> sounding = 3.0 / 4
                    }
                    i++
                }
            }
        }
        return out
    }

    /** a note of length [len] (4 a quarter) at the tempo, lengthened by [dot], in ms */
    private fun noteMs(len: Int, dot: Double): Int = (60000.0 / tempo * 4 / len * dot).roundToInt()

    companion object {
        /** A to G's semitones above C */
        private val SEMITONES = intArrayOf(9, 11, 0, 2, 4, 5, 7)

        /** note [n] (PLAY's: 0 octave 0's C; octave 3 starts at middle C, its A 440 Hz) in Hz */
        fun frequency(n: Int): Double = 440.0 * 2.0.pow((n - 45) / 12.0)
    }
}
