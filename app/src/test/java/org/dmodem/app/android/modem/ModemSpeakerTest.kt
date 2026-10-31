// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** The speaker: played only while the modem has it on (and the app lets it), at ATL's volume, let go when off. */
class ModemSpeakerTest {
    private class FakeSink : ModemSpeaker.Sink {
        var bytes = 0
        var volume = -1f
        var closed = false
        /** bytes played, counted */
        override fun write(pcm: ByteArray, n: Int) { bytes += n }
        /** the volume, kept */
        override fun volume(v: Float) { volume = v }
        /** closed, noted */
        override fun close() { closed = true }
    }

    /** nothing played while off, ATL's volume when on, the player let go off, and the app's switch over the modem's */
    @Test
    fun onlyWhileOn() {
        val sinks = ArrayList<FakeSink>()
        val spk = ModemSpeaker { FakeSink().also { sinks.add(it) } }
        val block = ByteArray(320)
        // off: nothing made, nothing played
        spk.play(block, block.size)
        assertEquals(0, sinks.size)
        // ATM1 off hook at ATL2
        spk.setLevel(2)
        spk.play(block, block.size)
        spk.play(block, block.size)
        assertEquals(1, sinks.size)
        assertEquals(640, sinks[0].bytes)
        assertEquals(0.6f, sinks[0].volume)
        // ATL3 meanwhile
        spk.setLevel(3)
        assertEquals(1f, sinks[0].volume)
        // CONNECT: off, the player let go
        spk.setLevel(0)
        assertTrue(sinks[0].closed)
        spk.play(block, block.size)
        assertEquals(640, sinks[0].bytes)
        assertEquals(1, sinks.size)
        // the app's switch off: quiet whatever the modem says
        spk.enabled = false
        spk.setLevel(3)
        spk.play(block, block.size)
        assertEquals(1, sinks.size)
        spk.enabled = true
        spk.play(block, block.size)
        assertEquals(2, sinks.size)
    }

    /** what the modem's speaker socket sends, all played, at ATL1's volume */
    @Test
    fun fromItsSocket() {
        val sink = FakeSink()
        val spk = ModemSpeaker { sink }
        spk.setLevel(1)
        val out = PipedOutputStream()
        val t = spk.start(PipedInputStream(out, 65536))
        out.write(ByteArray(8000))
        out.close()
        t.join(5000)
        assertEquals(8000, sink.bytes)
        assertEquals(0.3f, sink.volume)
    }
}
