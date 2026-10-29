// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.tones

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The countries' tones (D-Modem-RE's ITU table): found by any of their
 * names; their sound, the right frequencies in the right cadence.
 */
class CountryTonesTest {
    /** [f]'s amplitude in [x] (Goertzel), as a share of full scale */
    private fun level(x: ShortArray, f: Double, rate: Int = 8000, from: Int = 0, n: Int = x.size - from): Double {
        val k = 2 * cos(2 * PI * f / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until from + n) {
            val s = x[i] / 32768.0 + k * s1 - s2
            s2 = s1
            s1 = s
        }
        return 2 * sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n
    }

    /** the table: every country once, found by any of its names, the USA's and others' tones as the ITU has them, all audible and cadenced */
    @Test
    fun theTable() {
        assertEquals(192, CountryTones.all.size)
        assertEquals(CountryTones.all.size, CountryTones.all.map { it.name }.toSet().size)
        val us = assertNotNull(CountryTones.find("usa"))
        assertEquals("UNITED_STATES_OF_AMERICA", us.name)
        assertEquals(us, CountryTones.find(""))
        assertEquals(us, CountryTones.find("United States of America"))
        assertEquals("350+440 Hz, continuous", us.dial.toString())
        assertEquals("480+620 Hz, 0.5 on 0.5 off", us.busy.toString())
        assertEquals("440+480 Hz, 2 on 4 off", us.ringing.toString())
        assertEquals(20, us.ringHz)
        val uk = assertNotNull(CountryTones.find("UK"))
        assertEquals("United Kingdom of Great Britain and Northern Ireland", uk.label)
        assertEquals("400+450 Hz, 0.4 on 0.2 off, 0.4 on 2 off", uk.ringing.toString())
        assertEquals("400×15 Hz, 1 on 2 off", CountryTones.find("japan")!!.ringing.toString())
        assertEquals("RUSSIAN_FEDERATION", CountryTones.find("russia")!!.name)
        assertEquals("Hong Kong, China", CountryTones.find("hong-kong")!!.label)
        assertEquals("Bosnia and Herzegovina", CountryTones.find("BOSNIA_AND_HERZEGOVINA")!!.label)
        assertNull(CountryTones.find("ATLANTIS"))
        // every one with a cadenced ringing tone and audible tones
        for (c in CountryTones.all) {
            assertTrue(c.ringing.cadence.isNotEmpty(), c.name)
            for (t in listOf(c.ringing, c.dial, c.busy, c.congestion)) assertTrue(t.f1 in 100..3000 || t.f1 == 0, "${c.name}: $t")
        }
    }

    /** a dial tone's sound: both its frequencies at half the level, nothing else, faded in and out */
    @Test
    fun aDialToneSound() {
        val us = CountryTones.find("USA")!!
        val x = ToneSynth.pcm(us.dial, 1000, level = 0.4)
        assertEquals(8000, x.size)
        // both frequencies, each at half the level; nothing at the busy tone's
        assertTrue(abs(level(x, 350.0) - 0.2) < 0.02, "${level(x, 350.0)}")
        assertTrue(abs(level(x, 440.0) - 0.2) < 0.02, "${level(x, 440.0)}")
        assertTrue(level(x, 620.0) < 0.01)
        // faded in and out: no click at either end
        assertTrue(abs(x[0].toInt()) < 2000 && abs(x.last().toInt()) < 2000)
    }

    /** a busy tone's cadence: on and off in turn, ramped not clicking, and started from a point in it */
    @Test
    fun aBusyTonesCadence() {
        val us = CountryTones.find("USA")!!
        val x = ToneSynth.pcm(us.busy, 2000)
        // on for 0.5 s, off for 0.5 s, twice
        for (half in 0 until 4) {
            val l = level(x, 480.0, from = half * 4000 + 400, n = 3200)
            if (half % 2 == 0) assertTrue(l > 0.1, "$half: $l") else assertTrue(l < 0.001, "$half: $l")
        }
        // a burst's start ramps (4 ms), it doesn't click
        assertTrue(abs(x[4000 + 1].toInt()) < 1000)
        // from a point in the cadence: the off half first
        val later = ToneSynth.pcm(us.busy, 400, fromMs = 500)
        assertTrue(level(later, 480.0) < 0.001)
    }

    /** Japan's ringing tone: 400 Hz swung at 15 Hz (sidebands), on and off in its cadence */
    @Test
    fun aModulatedTone() {
        // Japan's ringing tone: 400 Hz, its level swung at 15 Hz (sidebands at 385 and 415)
        val jp = CountryTones.find("JAPAN")!!.ringing
        val x = ToneSynth.pcm(jp, 1000)
        assertTrue(level(x, 400.0) > 0.1)
        assertTrue(level(x, 415.0) > 0.02 && level(x, 385.0) > 0.02)
        assertTrue(jp.onAt(0.5) && !jp.onAt(1.5) && jp.onAt(3.2))
    }

    /** tones played in 50 ms pieces are the same samples as in one, with no jump bigger than the tone's own slope */
    @Test
    fun inPiecesAsInOne() {
        // a player's 50 ms chunks (the last one fading out) are the same sound as one long one: no clicks between them
        for (name in listOf("USA", "UK", "JAPAN", "GERMANY")) {
            val c = CountryTones.find(name)!!
            for (tone in listOf(c.dial, c.ringing, c.busy, c.congestion)) {
                val whole = ToneSynth.pcm(tone, 3000)
                val pieces = (0 until 3000 step 50).flatMap { at ->
                    ToneSynth.pcm(tone, 50, fromMs = at, fadeOut = at + 50 >= 3000).toList()
                }.toShortArray()
                assertEquals(whole.toList(), pieces.toList(), "$name $tone")
                // and nowhere a jump bigger than the highest tone's own slope (a click is one)
                val maxStep = 2 * PI * maxOf(tone.f1, tone.f2) * 0.3 * 32767 / 8000 * 1.05 + 2
                for (i in 1 until whole.size) {
                    assertTrue(abs(whole[i] - whole[i - 1]) <= maxStep, "$name $tone: a jump at sample $i")
                }
            }
        }
    }
}
