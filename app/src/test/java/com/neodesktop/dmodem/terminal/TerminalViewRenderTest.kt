// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.modem.DteHub
import com.neodesktop.dmodem.settings.TerminalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The view drawn for real (Robolectric's native graphics): ANSI art in
 * CP437 at 80 columns, the cells' colours checked, the picture kept in
 * build/terminal-render.png to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TerminalViewRenderTest {

    private val w = 960
    private val h = 520

    private fun render(art: ByteArray, settings: TerminalSettings = TerminalSettings()): Pair<Bitmap, TerminalView> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val session = TerminalSession(DteHub()) { null }
        session.charset = TermCharset.CP437
        val v = TerminalView(ctx)
        v.session = session
        v.applySettings(settings.copy(cursorBlink = false))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        synchronized(session.emulator) { session.emulator.append(art) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp to v
    }

    private fun cp437(s: String): ByteArray = TermCharset.CP437.encode(s)

    private fun rgb(p: Int) = p and 0xffffff

    @Test
    fun ansiArtInCp437() {
        val e = "\u001b"
        val art = cp437(
            "$e[0;1;37;44m╔══════════════════╗$e[0m  $e[31m█▓▒░$e[0m  $e[1;33mBold yellow$e[0m\r\n" +
                "$e[1;37;44m║  D-Modem BBS     ║$e[0m  $e[7mInverse$e[0m  $e[4mUnder$e[0m\r\n" +
                "$e[1;37;44m╚══════════════════╝$e[0m  $e[38;5;208mOrange$e[0m $e[48;2;0;128;0m green bg $e[0m\r\n" +
                "┌─┬─┐ ▀▄▌▐ ╒═╤═╕\r\n" +
                "│ │ │ ▖▗▘▝ ╞═╪═╡\r\n" +
                "└─┴─┘ ■□▪▫ ╘═╧═╛\r\n",
        )
        val (bmp, v) = render(art)
        val out = File("build/terminal-render.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val e2 = v.session!!.emulator
        assertEquals(80, e2.cols)
        val cw = w / 80f
        val ch = h.toFloat() / e2.rows
        fun center(col: Int, row: Int) = bmp.getPixel((col * cw + cw / 2).toInt(), (row * ch + ch / 2).toInt())

        // the box's inside: VGA blue (bold white on blue)
        assertEquals(0x0000aa, rgb(bmp.getPixel((5 * cw + 1).toInt(), (1 * ch + 2).toInt())))
        // the full block in red: its cell all red (drawn, not a glyph with gaps)
        val blockCol = 22
        for (dy in listOf(1, (ch / 2).toInt(), (ch - 2).toInt())) {
            assertEquals(0xaa0000, rgb(bmp.getPixel((blockCol * cw + cw / 2).toInt(), (0 * ch + dy).toInt())))
        }
        // the double line's horizontal: both of its lines reach the cell's edges (left and right)
        val lineRow = 0
        var lit = 0
        for (y in 0 until ch.toInt()) {
            val p = rgb(bmp.getPixel((3 * cw).toInt(), (lineRow * ch + y).toInt()))
            if (p == 0xffffff) lit++
        }
        assertTrue("the double line's two lines at the cell's left edge ($lit lit)", lit >= 2)
        // the inverse word: its background the default foreground (VGA's light grey)
        val inv = 25
        assertEquals(0xaaaaaa, rgb(bmp.getPixel((inv * cw + 1).toInt(), (1 * ch + 1).toInt())))
        // the 24-bit green background
        assertEquals(0x008000, rgb(bmp.getPixel((32 * cw + 1).toInt(), (2 * ch + 1).toInt())))
        // the screen's background elsewhere: black
        assertEquals(0x000000, rgb(center(70, 10)))
        println("rendered: ${out.absolutePath}")
    }

    @Test
    fun boldAsBrightOrNot() {
        val art = cp437("\u001b[1;34m█\u001b[0m")
        val (bright, _) = render(art, TerminalSettings(boldBright = true))
        val (plain, _) = render(art, TerminalSettings(boldBright = false))
        val cw = w / 80
        assertEquals(0x5555ff, rgb(bright.getPixel(cw / 2, 3)))
        assertEquals(0x0000aa, rgb(plain.getPixel(cw / 2, 3)))
    }

    @Test
    fun iceColorsMakeBlinkABrightBackground() {
        val art = cp437("\u001b[5;41m \u001b[0m")
        val (ice, _) = render(art, TerminalSettings(iceColors = true))
        assertEquals(0xff5555, rgb(ice.getPixel(2, 3)))
    }
}
