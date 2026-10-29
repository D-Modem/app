// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.tones

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A tone of a telephone network, as E.180 lists them: one frequency, or
 * two, or one modulated at [mod] Hz ("400×15"), on and off in turn in its
 * [cadence] (seconds, from on); an empty cadence: continuous.
 */
class Tone(val f1: Int, val f2: Int, val mod: Float, val cadence: FloatArray) {
    /** a cadence's period (s), 0 if continuous */
    val period: Double get() = cadence.sumOf { it.toDouble() }

    /** whether it's on at [t] seconds from its start */
    fun onAt(t: Double): Boolean {
        if (cadence.isEmpty()) return true
        var x = t % period
        for (i in cadence.indices) {
            if (x < cadence[i]) return i % 2 == 0
            x -= cadence[i]
        }
        return false
    }

    /**
     * at [t] seconds from its start: (seconds since this burst came on,
     * seconds until it goes off; infinite for a continuous tone), or null
     * while it's off
     */
    fun burst(t: Double): Pair<Double, Double>? {
        if (cadence.isEmpty()) return t to Double.POSITIVE_INFINITY
        var x = t % period
        for (i in cadence.indices) {
            if (x < cadence[i]) return if (i % 2 == 0) x to cadence[i] - x else null
            x -= cadence[i]
        }
        return null
    }

    /** how it's written in E.180 ("350+440 Hz, continuous", "480+620 Hz, 0.5 on 0.5 off") */
    override fun toString(): String {
        val f = when {
            f2 > 0 -> "$f1+$f2 Hz"
            mod > 0 -> "$f1×${fmt(mod)} Hz"
            else -> "$f1 Hz"
        }
        if (cadence.isEmpty()) return "$f, continuous"
        return f + ", " + cadence.toList().chunked(2).joinToString(", ") { p ->
            "${fmt(p[0])} on" + (p.getOrNull(1)?.let { " ${fmt(it)} off" } ?: "")
        }
    }

    /** [v] without ".0" when it is whole */
    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

/**
 * A country's call progress tones, from D-Modem-RE's table (the ITU's
 * list, after E.180): the ringing tone a caller hears (the ring signal to
 * the called line keeps its cadence, at [ringHz]), the dial tone, the busy
 * tone (the called line busy) and the congestion tone (the network's lines
 * busy).
 */
class CountryTones(
    /** upper case, words joined by '_' (the modem's name for it too) */
    val name: String,
    val ringing: Tone,
    val ringHz: Int,
    val dial: Tone,
    val busy: Tone,
    val congestion: Tone,
) {
    /** "United States of America", "Korea", "Hong Kong, China" */
    val label: String get() = label(name)

    companion object {
        /** the countries, by name */
        val all: List<CountryTones> get() = CountryTonesTable.countries

        /** the modem's default (an empty country): the USA's */
        const val DEFAULT = "UNITED_STATES_OF_AMERICA"

        /**
         * the country named [name] (the table's, or another name of it:
         * USA, UK, Russia), case, spaces, '-' and '.' as '_'; empty: the
         * default's. Null if there's none
         */
        fun find(name: String): CountryTones? {
            val n = norm(name.ifBlank { DEFAULT })
            val real = CountryTonesTable.aliases[n] ?: n
            return all.firstOrNull { it.name == real }
        }

        /** [s] as the table's names are: upper case, spaces, '-' and '.' as '_' */
        private fun norm(s: String) = s.trim().uppercase().map { if (it == ' ' || it == '-' || it == '.') '_' else it }.joinToString("")

        private val small = setOf("OF", "AND", "THE", "DA", "DU", "DE")

        /** a table name as a person writes it */
        fun label(name: String): String = when (name) {
            "HONG_KONG_CHINA" -> "Hong Kong, China"
            "MACAO_CHINA" -> "Macao, China"
            "LAO_PEOPLES_DEMOCRATIC_REPUBLIC" -> "Lao People's Democratic Republic"
            else -> name.split('_').mapIndexed { i, w ->
                val l = w.lowercase()
                if (i > 0 && w in small) l else l.replaceFirstChar { it.uppercase() }
            }.joinToString(" ")
        }
    }
}

/** A tone's sound: 16-bit samples, at the network's pace. */
object ToneSynth {
    /**
     * [ms] of [tone] at [rate] Hz, from [fromMs] into its cadence, peaking
     * at [level] of full scale (two frequencies each at half). Each burst's
     * edges ramp over 4 ms, by where it is in the cadence, so one sound in
     * pieces is the same as in one (a player's chunks: no clicks between
     * them); the end of the sound fades too, if [fadeOut].
     */
    fun pcm(tone: Tone, ms: Int, rate: Int = 8000, level: Double = 0.3, fromMs: Int = 0, fadeOut: Boolean = true): ShortArray {
        val n = (ms.toLong() * rate / 1000).toInt()
        val out = ShortArray(n)
        val ramp = RAMP_S * rate
        val peak = level * 32767
        val start = fromMs.toLong() * rate / 1000
        for (i in 0 until n) {
            // (the sample's time from the tone's start: from its count, so the pieces line up exactly)
            val t = (start + i).toDouble() / rate
            val (since, until) = tone.burst(t) ?: continue
            var g = minOf(1.0, since / RAMP_S, until / RAMP_S)
            if (fadeOut) g = minOf(g, (n - 1 - i) / ramp)
            if (g <= 0) continue
            var x = sin(2 * PI * tone.f1 * t)
            if (tone.f2 > 0) x = (x + sin(2 * PI * tone.f2 * t)) / 2
            if (tone.mod > 0) x *= 0.6 + 0.4 * sin(2 * PI * tone.mod * t)
            out[i] = (x * peak * g).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** how long a burst's edges ramp (s) */
    const val RAMP_S = 0.004
}
