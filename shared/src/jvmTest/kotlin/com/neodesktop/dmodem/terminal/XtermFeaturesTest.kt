// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * xterm's extensions as programs use them: wide and combining
 * characters, the underlines, the mouse and focus reports, the colours
 * asked about and set, the clipboard, hyperlinks, the reports (DA, DECRQM,
 * DECRQSS, XTWINOPS, XTSMGRAPHICS) and sixel images.
 */
class XtermFeaturesTest {

    private val replies = StringBuilder()
    private fun term(cols: Int = 20, rows: Int = 6) =
        TerminalEmulator(cols, rows, 100) { replies.append(String(it, Charsets.UTF_8)) }

    private fun TerminalEmulator.send(s: String) = append(s.toByteArray(Charsets.UTF_8))
    private fun TerminalEmulator.row(r: Int) = line(r).text()
    private fun takeReplies() = replies.toString().also { replies.setLength(0) }

    // characters ------------------------------------------------------

    @Test
    fun wideCharactersTakeTwoCells() {
        val t = term(10, 3)
        t.send("a漢字b")
        assertEquals("a漢字b", t.row(0))
        assertEquals(6, t.cursorX)
        assertEquals(Line.WIDE_TAIL, t.line(0).chars[2])
        // one column left: the wide character to the next line
        t.send("\r\u001b[9Cあ")
        assertEquals("い", "い") // (a sanity check of the source's encoding)
        assertEquals("あ", t.row(1))
        assertTrue(t.line(0).wrapped)
        // half of one written over: the other half blanked, not left orphaned
        t.send("\u001b[1;3HX")
        assertEquals("a X字b", t.row(0))
        // emoji: two cells
        t.send("\u001b[3;1H🙂x")
        assertEquals(3, t.cursorX)
    }

    @Test
    fun combiningMarksJoinTheirCharacter() {
        val t = term(10, 2)
        t.send("éx")
        assertEquals("éx", t.row(0))
        assertEquals(2, t.cursorX)
        // a ZWJ sequence: one cluster, the first emoji's two cells
        t.send("\r\n👩‍💻z")
        assertEquals("👩‍💻z", t.row(1))
        assertEquals(3, t.cursorX)
        assertEquals(Wcwidth.width('a'.code), 1)
        assertEquals(0, Wcwidth.width(0x0301))
        assertEquals(2, Wcwidth.width(0x1F600))
        assertEquals(2, Wcwidth.width(0xAC00))
    }

    @Test
    fun insertAndDeleteMoveTheExtras() {
        val t = term(10, 1)
        t.send("ab́c\u001b[1;2H\u001b[2@")
        assertEquals("a  b́c", t.row(0))
        t.send("\u001b[3P")
        assertEquals("ac", t.row(0))
    }

    // the rendition ---------------------------------------------------

    @Test
    fun underlineStylesAndTheUnderlinesColourReadPast() {
        val t = term()
        t.send("\u001b[4:3mA\u001b[4:0mB\u001b[21mC\u001b[24;53mD\u001b[0;58;2;255;0;0;1mE\u001b[58:2::1:2:3;3mF")
        val st = t.line(0).styles
        assertEquals(Style.CURLY_UNDERLINE, Style.attrs(st[0]) and Style.ANY_UNDERLINE)
        assertEquals(0, Style.attrs(st[1]) and Style.ANY_UNDERLINE)
        assertEquals(Style.DOUBLE_UNDERLINE, Style.attrs(st[2]) and Style.ANY_UNDERLINE)
        assertEquals(Style.OVERLINE, Style.attrs(st[3]))
        // the underline's colour's numbers aren't taken for attributes (2: faint, 0: reset)
        assertEquals(Style.BOLD, Style.attrs(st[4]))
        assertEquals(Style.BOLD or Style.ITALIC, Style.attrs(st[5]))
    }

    // reports -----------------------------------------------------------

    @Test
    fun theReportsProgramsAskFor() {
        val t = term(80, 24)
        t.setCellPixels(9, 18)
        t.send("\u001b[c")
        assertEquals("\u001b[?62;4;22c", takeReplies())
        t.send("\u001b[>q")
        assertEquals("\u001bP>|D-Modem 1.0\u001b\\", takeReplies())
        t.send("\u001b[14t\u001b[16t\u001b[18t")
        assertEquals("\u001b[4;432;720t\u001b[6;18;9t\u001b[8;24;80t", takeReplies())
        t.send("\u001b[?1;1;0S\u001b[?2;1;0S")
        assertEquals("\u001b[?1;0;256S\u001b[?2;0;720;432S", takeReplies())
        // DECRQM
        t.send("\u001b[?2004h\u001b[?2004\$p\u001b[?1006\$p\u001b[?9999\$p\u001b[4\$p")
        assertEquals("\u001b[?2004;1\$y\u001b[?1006;2\$y\u001b[?9999;0\$y\u001b[4;2\$y", takeReplies())
        // DECRQSS: the rendition, the region, the cursor
        t.send("\u001b[1;38;5;196;48;2;1;2;3m\u001bP\$qm\u001b\\")
        assertEquals("\u001bP1\$r0;1;38;5;196;48;2;1;2;3m\u001b\\", takeReplies())
        t.send("\u001b[2;10r\u001bP\$qr\u001b\\\u001b[5 q\u001bP\$q q\u001b\\\u001bP\$qx\u001b\\")
        assertEquals("\u001bP1\$r2;10r\u001b\\\u001bP1\$r5 q\u001b\\\u001bP0\$r\u001b\\", takeReplies())
        assertEquals(5, t.cursorStyle)
    }

    @Test
    fun coloursAskedAboutAndSet() {
        val t = term()
        t.defaultColor = { if (it == 257) 0xff102030.toInt() else TerminalEmulator.xtermColor(it) }
        t.send("\u001b]11;?\u0007")
        assertEquals("\u001b]11;rgb:1010/2020/3030\u0007", takeReplies())
        t.send("\u001b]4;1;#ff8000\u001b\\\u001b]4;1;?\u001b\\")
        assertEquals("\u001b]4;1;rgb:ffff/8080/0000\u001b\\", takeReplies())
        assertEquals(0xffff8000.toInt(), t.colorOverride(1))
        t.send("\u001b]10;rgb:f/e/d;rgb:00/00/00\u0007")
        assertEquals(0xffffeedd.toInt(), t.colorOverride(256))
        assertEquals(0xff000000.toInt(), t.colorOverride(257))
        t.send("\u001b]104\u0007\u001b]110\u0007")
        assertEquals(-1, t.colorOverride(1))
        assertEquals(-1, t.colorOverride(256))
        assertEquals(0xff000000.toInt(), t.colorOverride(257))
        assertNull(TerminalEmulator.parseColor("red"))
    }

    @Test
    fun theClipboardAndHyperlinks() {
        val t = term()
        val copied = ArrayList<String>()
        t.listener = object : TerminalEmulator.Listener {
            override fun onClipboard(text: String) { copied.add(text) }
        }
        t.send("\u001b]52;c;" + java.util.Base64.getEncoder().encodeToString("héllo".toByteArray()) + "\u0007")
        // asked what's on it: not told
        t.send("\u001b]52;c;?\u0007")
        assertEquals(listOf("héllo"), copied)
        assertEquals("", takeReplies())
        t.send("see \u001b]8;;https://example.org/x\u001b\\here\u001b]8;;\u001b\\ ok")
        val l = t.line(0)
        assertEquals("https://example.org/x", l.extra(4)?.link)
        assertEquals("https://example.org/x", l.extra(7)?.link)
        assertNull(l.extra(9)?.link)
    }

    // the mouse ---------------------------------------------------------

    @Test
    fun mouseReportsInEachEncoding() {
        val t = term(300, 50)
        assertNull("none asked for", t.mouseEvent(0, 1, 1, press = true))
        t.send("\u001b[?1000h")
        assertArrayEquals(byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), 32, 34, 35), t.mouseEvent(0, 1, 2, press = true))
        assertArrayEquals(byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), 35, 34, 35), t.mouseEvent(0, 1, 2, press = false))
        assertNull("motion only for 1002/1003", t.mouseEvent(0, 1, 2, press = true, motion = true))
        assertNull("past 223: not in the old form", t.mouseEvent(0, 250, 2, press = true))
        t.send("\u001b[?1006h")
        assertEquals("\u001b[<0;251;3M", String(t.mouseEvent(0, 250, 2, press = true)!!))
        assertEquals("\u001b[<0;251;3m", String(t.mouseEvent(0, 250, 2, press = false)!!))
        assertEquals("\u001b[<65;5;6M", String(t.mouseEvent(65, 4, 5, press = true)!!))
        assertNull("no wheel releases", t.mouseEvent(65, 4, 5, press = false))
        assertEquals("ctrl", "\u001b[<16;1;1M", String(t.mouseEvent(0, 0, 0, press = true, mods = 16)!!))
        t.send("\u001b[?1002h")
        assertEquals("\u001b[<32;2;2M", String(t.mouseEvent(0, 1, 1, press = true, motion = true)!!))
        t.send("\u001b[?1006l\u001b[?1015h")
        assertEquals("\u001b[32;2;2M", String(t.mouseEvent(0, 1, 1, press = true)!!))
        t.send("\u001b[?1002l")
        assertNull(t.mouseEvent(0, 1, 1, press = true))
        // X10: presses only
        t.send("\u001b[?9h\u001b[?1015l")
        assertNotNull(t.mouseEvent(0, 1, 1, press = true))
        assertNull(t.mouseEvent(0, 1, 1, press = false))
        // focus
        assertNull(t.focusReport(true))
        t.send("\u001b[?1004h")
        assertEquals("\u001b[I", String(t.focusReport(true)!!))
        assertEquals("\u001b[O", String(t.focusReport(false)!!))
        t.reset()
        assertEquals(TerminalEmulator.MouseMode.NONE, t.mouseMode)
    }

    // sixel ------------------------------------------------------------

    private fun resource(name: String): File =
        // (the app's test images: from this module, or the project's root)
        listOf(File("../app/src/test/resources/$name"), File("app/src/test/resources/$name")).first { it.exists() }

    /** img2sixel's image (libsixel) decoded: every pixel the picture's, within libsixel's rounding of colours to percent */
    @Test
    fun aSixelImageAsImg2sixelWritesIt() {
        val png = ImageIO.read(resource("sixel/test.png"))
        val six = resource("sixel/test.six").readBytes()
        val t = term(40, 10)
        t.setCellPixels(8, 16)
        t.send("ab")
        t.append(six)
        t.send("z")
        // the image: 64x40 pixels, 8 cells by 3, at the cursor (column 2), the cursor then below it
        val tile = t.line(0).extra(2)?.image
        assertNotNull(tile)
        val img = tile!!.image
        assertEquals(64, img.width)
        assertEquals(40, img.height)
        for (y in 0 until 40) for (x in 0 until 64) {
            val want = png.getRGB(x, y)
            val got = img.pixels[y * 64 + x]
            for (shift in listOf(16, 8, 0)) {
                val d = abs(((want shr shift) and 0xff) - ((got shr shift) and 0xff))
                assertTrue("($x,$y): ${Integer.toHexString(want)} vs ${Integer.toHexString(got)}", d <= 10)
            }
        }
        for (ty in 0 until 3) for (tx in 0 until 8) {
            val e = t.line(ty).extra(2 + tx)?.image
            assertEquals(tx, e?.tx)
            assertEquals(ty, e?.ty)
        }
        assertNull(t.line(0).extra(10)?.image)
        assertEquals("ab", t.row(0))
        assertEquals("the line after it, at its left", "  z", t.row(3))
    }

    @Test
    fun sixelsColoursRepeatsAndTransparency() {
        val t = term(20, 4)
        t.setCellPixels(4, 6)
        // HLS 120;50;100 is DEC's red; a repeat; a new line; P2=1: transparent
        t.send("\u001bP0;1q#1;1;120;50;100#1!5~-#2;2;0;100;0~~\u001b\\")
        val img = t.line(0).extra(0)!!.image!!.image
        assertEquals(5, img.width)
        assertEquals(12, img.height)
        assertEquals(0xffff0000.toInt(), img.pixels[0])
        assertEquals(0xffff0000.toInt(), img.pixels[4 + 5 * 5])
        assertEquals(0xff00ff00.toInt(), img.pixels[6 * 5 + 1])
        assertEquals("transparent", 0, img.pixels[6 * 5 + 2])
        // not transparent: register 0, black by default, behind
        t.send("\u001bPq\"1;1;3;6#3;2;0;0;100#3!2@\u001b\\")
        val img2 = t.line(2).extra(0)!!.image!!.image
        assertEquals(3, img2.width)
        assertEquals(0xff0000ff.toInt(), img2.pixels[0])
        assertEquals(0xff000000.toInt(), img2.pixels[2])
        assertEquals(0xff000000.toInt(), img2.pixels[3])
    }

    @Test
    fun imagesScrollWithTheText() {
        val t = term(10, 3)
        t.setCellPixels(4, 6)
        t.send("\u001bPq#1;2;100;0;0#1!8~-!8~\u001b\\")
        // 8x12: 2 cells by 2; the cursor below it, on row 2
        assertEquals(2, t.cursorY)
        val tile = t.line(0).extra(0)?.image
        assertNotNull(tile)
        t.send("\r\n")
        // scrolled up one: its top row in the history, its second on the screen's first line
        assertEquals(0, t.line(-1).extra(0)?.image?.ty)
        assertEquals(1, t.line(0).extra(1)?.image?.ty)
        // text over it: that cell's tile gone
        t.send("\u001b[1;1HX")
        assertNull(t.line(0).extra(0)?.image)
        assertNotNull(t.line(0).extra(1)?.image)
    }

    @Test
    fun aDcsCutShortOrUnknownLeavesTheTerminalWorking() {
        val t = term(10, 2)
        t.send("\u001bPq#1;2;100;0;0~~\u0018after")
        assertEquals("after", t.row(0))
        t.send("\r\n\u001bP+q544e\u001b\\ok")
        assertEquals("\u001bP0+r\u001b\\", takeReplies())
        assertEquals("ok", t.row(1))
    }
}
