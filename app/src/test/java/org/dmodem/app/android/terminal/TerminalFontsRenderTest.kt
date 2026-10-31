// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import org.dmodem.app.terminal.TermCharset
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.dmodem.app.android.modem.DteHub
import org.dmodem.app.android.settings.TerminalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every bundled font: it loads (not the system's in its place), its cells
 * stay on the grid with glyphs it hasn't got (a fallback font's), and a
 * picture of them all in build/terminal-fonts.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TerminalFontsRenderTest {

    private val w = 960
    private val h = 66

    /** each bundled font loaded (not the system's), a block on its cell at column 40 with fallback glyphs before it, all drawn to build/terminal-fonts.png */
    @Test
    fun everyFontLoadsAndKeepsTheGrid() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sheet = Bitmap.createBitmap(w, h * TerminalFont.entries.size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = 0xffffff00.toInt() }
        for ((i, font) in TerminalFont.entries.withIndex()) {
            if (font.asset != null) {
                val tf = FontCache.typeface(ctx, font, false)
                assertNotSame("${font.label} loaded", Typeface.MONOSPACE, tf)
                assertNotNull(ctx.assets.open(font.asset!!))
            }
            val session = TerminalSession(DteHub()) { null }
            session.charset = TermCharset.CP437
            val v = TerminalView(ctx)
            v.session = session
            v.applySettings(TerminalSettings(font = font, fixedColumns = 80, cursorBlink = false, ligatures = true))
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val e = "\u001b"
            // glyphs not every font has (⌐ ≡ ⌠ ⌡ ∙), then a red block at column 40
            val text = "${font.label.take(18).padEnd(18)} 0O1lI -> != ⌐≡⌠⌡∙±÷"
            val art = TermCharset.CP437.encode(text.padEnd(40).take(40) + "$e[31m█$e[0m $e[1;32mBold$e[0m ╔═╗░▒▓")
            synchronized(session.emulator) { session.emulator.append(art) }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            sc.drawBitmap(bmp, 0f, (i * h).toFloat(), null)
            sc.drawText(font.name, w - 200f, i * h + h - 6f, label)

            assertEquals(80, session.emulator.cols)
            val cw = w / 80f
            val red = bmp.getPixel((40 * cw + cw / 2).toInt(), 4) and 0xffffff
            assertEquals("${font.label}: the block at column 40 on its cell", 0xaa0000, red)
            // the cell before it is the background: nothing pushed into it
            val before = bmp.getPixel((39 * cw + cw / 2).toInt(), 4) and 0xffffff
            assertTrue("${font.label}: column 39 empty (0x${Integer.toHexString(before)})", before == 0)
        }
        File("build/terminal-fonts.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** each variable font's bold a real one: a W in it with more ink than the regular's */
    @Test
    fun variableFontsHaveARealBold() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        for (f in TerminalFont.entries.filter { it.variable }) {
            val p = Paint().apply { textSize = 100f }
            p.typeface = FontCache.typeface(ctx, f, false)
            val regular = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            Canvas(regular).drawText("W", 0f, 100f, p)
            p.typeface = FontCache.typeface(ctx, f, true)
            val bold = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            Canvas(bold).drawText("W", 0f, 100f, p)
            /** how many of [b]'s pixels are inked (mostly opaque) */
            fun ink(b: Bitmap): Int {
                var n = 0
                for (y in 0 until 120) for (x in 0 until 120) if ((b.getPixel(x, y) ushr 24) > 128) n++
                return n
            }
            assertTrue("${f.label}: bold has more ink (${ink(bold)} > ${ink(regular)})", ink(bold) > ink(regular) * 1.15)
        }
    }
}
