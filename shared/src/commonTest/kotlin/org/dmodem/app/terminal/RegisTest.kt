// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class RegisTest {

    private val replies = StringBuilder()

    /** an 80x24 VT340 (10x20 cells: its 800x480 picture a pixel a pixel), whose replies are kept */
    private fun term(profile: TerminalProfile = TerminalProfiles.VT340): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(80, 24, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** ReGIS [s] in its DCS (P1 0: going on from where the last left off) */
    private fun TerminalEmulator.regis(s: String) = send("\u001bPp$s\u001b\\")

    /** the picture's tile at cell ([x], [y]), or null */
    private fun TerminalEmulator.tile(x: Int, y: Int): ImageTile? = line(y).extra(x)?.image?.takeIf { it.image.picture }

    /** the picture's pixel at ([px], [py]) (0: nothing drawn), or 0 where there's no picture */
    private fun TerminalEmulator.pixel(px: Int, py: Int): Int {
        val tile = tile(px / 10, py / 20) ?: return 0
        return tile.image.pixels[py * tile.image.width + px]
    }

    /** P moves, V draws from the position to each one given; relative parts and left-out ones */
    @Test
    fun vectors() {
        val t = term()
        t.regis("P[100,100]V[200,100][+0,+100]")
        assertTrue(t.pixel(150, 100) != 0)
        assertTrue(t.pixel(200, 150) != 0)
        assertEquals(0, t.pixel(150, 150))
        assertEquals(0, t.pixel(50, 100))
        // R(P): the position is the last one drawn to
        t.regis("R(P)")
        assertEquals("[200,200]\r", replies.toString())
    }

    /** W(I): the colour register; S(M): a register's colour (HLS); S(E) the screen erased */
    @Test
    fun colours() {
        val t = term()
        t.regis("S(M3(H0L50S100))W(I3)P[10,10]V[50,10]")
        // (DEC's hue 0 is blue)
        assertEquals(0xff0000ff.toInt(), t.pixel(30, 10))
        t.regis("W(I(R))P[10,30]V[50,30]")
        // (the nearest register: the VT340's red)
        val red = t.pixel(30, 30)
        assertTrue((red shr 16 and 0xff) > 0x80 && (red and 0xff) < 0x40)
        t.regis("S(E)")
        assertEquals(0, t.pixel(30, 10))
        assertEquals(null, t.tile(3, 0))
    }

    /** a circle round the position, through the point given */
    @Test
    fun circle() {
        val t = term()
        t.regis("P[400,240]C[+100]")
        assertTrue(t.pixel(500, 240) != 0)
        assertTrue(t.pixel(300, 240) != 0)
        assertTrue(t.pixel(400, 140) != 0)
        assertEquals(0, t.pixel(400, 240))
    }

    /** F: the polygon its vectors go round filled */
    @Test
    fun fill() {
        val t = term()
        t.regis("P[100,100]F(V[200,100][200,200][100,200][100,100])")
        assertTrue(t.pixel(150, 150) != 0)
        assertEquals(0, t.pixel(250, 150))
    }

    /** T: a string drawn at the position, cell by cell; the position after it */
    @Test
    fun text() {
        val t = term()
        t.regis("P[0,0]T'AB'R(P)")
        assertTrue(t.tile(0, 0) != null)
        assertTrue(t.tile(1, 0) != null)
        assertEquals("[20,0]\r", replies.toString())
    }

    /** macrographs: defined, run (more than once), cleared */
    @Test
    fun macrographs() {
        val t = term()
        t.regis("@:AV[+50]@;P[0,100]@A@AR(P)")
        assertEquals("[100,100]\r", replies.toString())
        assertTrue(t.pixel(75, 100) != 0)
    }

    /** pixel vectors: 0 right, 6 down, the multiplier W(M) */
    @Test
    fun pixelVectors() {
        val t = term()
        t.regis("W(M10)P[100,100]V00066R(P)")
        assertEquals("[130,120]\r", replies.toString())
    }

    /** S(A): the address mapping, the user's coordinates over the screen */
    @Test
    fun addressMapping() {
        val t = term()
        t.regis("S(A[0,0][79,47])P[40,24]V[79,24]")
        // (24 of 47 is the screen's row 244)
        assertTrue(t.pixel(600, 244) != 0)
        assertEquals(0, t.pixel(300, 244))
    }

    /** DCS 1 p starts afresh: the position back at 0,0 */
    @Test
    fun restart() {
        val t = term()
        t.regis("P[100,100]")
        t.send("\u001bP1pR(P)\u001b\\")
        assertEquals("[0,0]\r", replies.toString())
    }

    /** a command split over two reads runs once it's whole */
    @Test
    fun split() {
        val t = term()
        t.send("\u001bP1pP[100,100]V[200,")
        assertEquals(0, t.pixel(150, 100))
        t.send("100]\u001b\\")
        assertTrue(t.pixel(150, 100) != 0)
    }

    /** a VT340 says it has ReGIS and sixel (DA1 3 and 4); a VT320 ignores ReGIS */
    @Test
    fun attributes() {
        val t = term()
        t.send("\u001b[c")
        assertEquals("\u001b[?63;1;2;3;4;6;7;8;9c", replies.toString())
        val v = term(TerminalProfiles.VT320)
        v.regis("P[0,0]V[799,0]")
        assertEquals(null, v.tile(0, 0))
    }
}
