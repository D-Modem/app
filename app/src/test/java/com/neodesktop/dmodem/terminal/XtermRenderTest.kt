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
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * xterm's extensions drawn (Robolectric's native graphics): wide and
 * combining characters, the underlines, a palette colour the remote set,
 * a sixel image (libsixel's img2sixel's), kept in build/xterm-render.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class XtermRenderTest {

    private val w = 1080
    private val h = 760

    private fun resource(name: String): File =
        listOf(File("src/test/resources/$name"), File("app/src/test/resources/$name")).first { it.exists() }

    @Test
    fun theExtensionsDrawn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val session = TerminalSession(DteHub()) { null }
        session.charset = TermCharset.UTF8
        val v = TerminalView(ctx)
        v.session = session
        v.applySettings(TerminalSettings(themeId = "xterm", fixedColumns = 60, cursorBlink = false, charset = TermCharset.UTF8))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val e = session.emulator
        val esc = "\u001b"
        val text = "${esc}[1mxterm extensions${esc}[0m\r\n" +
            "Wide: 漢字かな한국어 🙂🚀👩‍💻  combining: é ñ ä\r\n" +
            "${esc}[4mplain${esc}[0m ${esc}[4:3;38;5;196mcurly${esc}[0m ${esc}[21mdouble${esc}[0m ${esc}[53moverline${esc}[0m ${esc}[9mstrike${esc}[0m\r\n" +
            "${esc}]4;2;#ff8800${esc}\\${esc}[32mgreen set to orange by OSC 4${esc}[0m  ${esc}]8;;https://example.org${esc}\\a link${esc}]8;;${esc}\\\r\n" +
            "${esc}[38;2;255;105;180mtruecolor pink${esc}[0m ${esc}[48;5;27m 256-colour blue ${esc}[0m\r\n"
        synchronized(e) {
            e.append(text.toByteArray())
            e.append(resource("sixel/picture.six").readBytes())
            e.append("${esc}[5 q\$ ".toByteArray())
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/xterm-render.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val cw = w / 60f
        val ch: Float
        val imgRow: Int
        synchronized(e) {
            ch = h.toFloat() / e.rows
            imgRow = 5
            assertEquals("the image's first tile on row 5", 0, e.line(imgRow).extra(0)?.image?.ty)
        }
        // the image drawn at its place: its pixels (scaled to the cells) match the picture's
        val png = ImageIO.read(resource("sixel/picture.png"))
        val img = synchronized(e) { e.line(imgRow).extra(0)!!.image!!.image }
        val sx = cw / img.cellWidth
        val sy = ch / img.cellHeight
        var close = 0
        var total = 0
        for (py in 10 until png.height - 10 step 7) for (px in 10 until png.width - 10 step 7) {
            val want = png.getRGB(px, py)
            val got = bmp.getPixel((px * sx).toInt(), (imgRow * ch + py * sy).toInt())
            total++
            val d = listOf(16, 8, 0).maxOf { abs(((want shr it) and 0xff) - ((got shr it) and 0xff)) }
            if (d <= 48) close++
        }
        // (img2sixel dithers to its 256 colours: most samples close, not all)
        assertTrue("$close of $total close to the picture", close > total * 0.8)
        // OSC 4 made colour 2 orange: the text's pixels orange somewhere in its line
        val row3 = (3 * ch).toInt()
        var orange = false
        for (x in 0 until (30 * cw).toInt()) for (y in row3 until (row3 + ch).toInt()) {
            val p = bmp.getPixel(x, y)
            if (abs(((p shr 16) and 0xff) - 0xff) < 30 && abs(((p shr 8) and 0xff) - 0x88) < 40 && (p and 0xff) < 60) orange = true
        }
        assertTrue("OSC 4's orange drawn", orange)
    }
}
