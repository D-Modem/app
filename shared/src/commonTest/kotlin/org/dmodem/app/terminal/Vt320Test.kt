// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class Vt320Test {

    private val replies = StringBuilder()
    private val keys = KeyEncoder()

    /** a terminal of [profile] whose replies are kept */
    private fun term(profile: TerminalProfile = TerminalProfiles.VT320, cols: Int = 20, rows: Int = 4): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(cols, rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** the replies so far, then forgotten */
    private fun taken(): String = replies.toString().also { replies.setLength(0) }

    /** G2 and G3 designated and shifted in: locking (LS2, LS3), single (SS2, SS3, and their C1 forms), and GR's (LS1R-LS3R) */
    @Test
    fun shifts() {
        val t = term()
        // G2 DEC Graphics, G3 DEC Technical; q in G2 a line, a in G3 an alpha
        t.send("\u001b*0\u001b+>q\u001bNq\u001bOaq")
        assertEquals("q─αq", t.line(0).text().substring(0, 4))
        t.send("\r\u001bnq\u001boa\u000fq")
        assertEquals("─αq", t.line(0).text().substring(0, 3))
        // (C1 controls and GR: only when the line's octets are Latin-1's)
        t.decoder.charset = TermCharset.LATIN1
        t.send("\r\u008eq\u008fa")
        assertEquals("─α", t.line(0).text().substring(0, 2))
        // G1 DEC Graphics in GR by LS1R, then G2 Latin-1 by LS2R
        t.send("\r\u001b)0\u001b~ñ\u001b.A\u001b}é")
        assertEquals("─é", t.line(0).text().substring(0, 2))
        // a VT100 has no G2 or G3
        val v = term(TerminalProfiles.VT100)
        v.send("\u001b*0\u001bNq")
        assertEquals("q", v.line(0).text().substring(0, 1))
    }

    /** two-character designators, the national sets, 96-character sets, and DECSC keeping them */
    @Test
    fun designations() {
        val t = term()
        // DEC Supplemental in G0: 0x28 is ¤, 0x57 Œ
        t.send("\u001b(%5(W\u001b(B")
        assertEquals("¤Œ", t.line(0).text().substring(0, 2))
        // German in G0: [ is Ä, } ü
        t.send("\r\u001b(K[}\u001b(B")
        assertEquals("Äü", t.line(0).text().substring(0, 2))
        // Latin-1 as a 96-character set in G1, shifted into GL: A is Á
        t.send("\r\u001b-A\u000eA\u000f")
        assertEquals("Á", t.line(0).text().substring(0, 1))
        // saved and restored with the cursor
        t.send("\r\u001b(0\u001b7\u001b(B\u001b8q")
        assertEquals("─", t.line(0).text().substring(0, 1))
        // DEC Technical is a VT320's, not a VT220's
        val v = term(TerminalProfiles.VT220)
        v.send("\u001b(>a")
        assertEquals("a", v.line(0).text().substring(0, 1))
    }

    /** DECSCA protects what's written from DECSED and DECSEL, but not from ED and EL; SGR leaves it */
    @Test
    fun selectiveErase() {
        val t = term()
        t.send("ab\u001b[1\"q\u001b[0mCD\u001b[0\"qef")
        t.send("\u001b[?2K")
        assertEquals("  CD  ", t.line(0).text().padEnd(6))
        t.send("\u001b[2K")
        assertEquals("      ", t.line(0).text().padEnd(6))
        t.send("\r\u001b[1\"qX\u001b[0\"qy\r\u001b[?J")
        assertEquals("X ", t.line(0).text().padEnd(2))
        t.send("\u001bP\$q\"q\u001b\\")
        assertEquals("\u001bP1\$r0\"q\u001b\\", taken())
    }

    /** S8C1T's 8-bit replies, S7C1T's 7-bit, DECSCL's level and its report */
    @Test
    fun controlsAndLevel() {
        val t = term()
        t.send("\u001b G\u001b[5n")
        assertEquals("\u009b0n", taken())
        t.send("\u001b F\u001b[5n")
        assertEquals("\u001b[0n", taken())
        t.send("\u001b[62;1\"p\u001bP\$q\"p\u001b\\")
        // (at level 2 DECRQSS is gone: a VT220 hasn't got it)
        assertEquals("", taken())
        t.send("\u001b[63;0\"p\u001bP\$q\"p\u001b\\")
        assertEquals("\u0090" + "1\$r63;0\"p\u009c", taken())
        t.send("\u001bc\u001b[c")
        assertEquals("\u001b[?63;1;2;6;7;8;9c", taken())
        t.send("\u001b[>c")
        assertEquals("\u001b[>24;10;0c", taken())
    }

    /** DSR's DEC questions, DECCIR, DECTABSR and the preferred supplemental set */
    @Test
    fun reports() {
        val t = term()
        t.send("\u001b[?15n\u001b[?25n\u001b[?26n")
        assertEquals("\u001b[?10n\u001b[?20n\u001b[?27;1;0;0n", taken())
        t.send("\u001b[2;3H\u001b[?6n")
        assertEquals("\u001b[?2;3;1R", taken())
        t.send("\u001b[1;7m\u001b*0\u001b}\u001b[1\$w")
        assertEquals("\u001bP1\$u2;3;1;I;@;@;0;2;H;BB0A\u001b\\", taken())
        t.send("\u001b[3g\u001bH\u001b[2\$w")
        assertEquals("\u001bP2\$u3\u001b\\", taken())
        t.send("\u001b[&u")
        assertEquals("\u001bP1!uA\u001b\\", taken())
        t.send("\u001bP0!u%5\u001b\\\u001b[&u")
        assertEquals("\u001bP0!u%5\u001b\\", taken())
        // ESC ( < : the preferred set
        t.send("\u001b(<(")
        assertEquals("¤", t.line(1).text().substring(2, 3))
    }

    /** DECUDK: Shift and F6-F20 send what the host loaded, until it locks them and RIS clears them */
    @Test
    fun userDefinedKeys() {
        val t = term()
        // F6 "hi", F20 "ok", left unlocked
        t.send("\u001bP0;1|17/6869;34/6f6b\u001b\\")
        assertContentEquals("hi".encodeToByteArray(), t.keyOctets(keys, Key.F6, shift = true))
        assertContentEquals("ok".encodeToByteArray(), t.keyOctets(keys, Key.F20, shift = true))
        assertEquals("\u001b[17~", t.keyOctets(keys, Key.F6).decodeToString())
        // more loaded, F6 kept (Pc 1); then locked
        t.send("\u001bP1;0|18/41\u001b\\")
        assertContentEquals("hi".encodeToByteArray(), t.keyOctets(keys, Key.F6, shift = true))
        assertContentEquals("A".encodeToByteArray(), t.keyOctets(keys, Key.F7, shift = true))
        t.send("\u001bP0;1|17/42\u001b\\\u001b[?25n")
        assertEquals("\u001b[?21n", taken())
        assertContentEquals("hi".encodeToByteArray(), t.keyOctets(keys, Key.F6, shift = true))
        t.send("\u001bc")
        assertEquals("\u001b[17;2~", t.keyOctets(keys, Key.F6, shift = true).decodeToString())
        // the LK201's other keys
        assertEquals("\u001b[28~", keys.key(Key.F15, false))
        assertEquals("\u001b[1~", keys.key(Key.FIND, false))
        assertNull(UserKeys().get(17))
    }
}
