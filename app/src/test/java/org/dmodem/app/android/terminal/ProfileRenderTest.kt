// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.dmodem.app.android.modem.DteHub
import org.dmodem.app.android.settings.TerminalSettings
import org.dmodem.app.terminal.TerminalProfile
import org.dmodem.app.terminal.TerminalProfiles
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every terminal type drawn for real with a screen of its own codes, each
 * kept as build/profiles/<id>.png to look at, and checked to have drawn
 * something (text, glyphs, mosaics, a picture) over its background.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileRenderTest {

    private val w = 960
    private val h = 600

    /** [octets] drawn by a view on a terminal of [profile]: the picture */
    private fun render(profile: TerminalProfile, octets: String): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val session = TerminalSession(DteHub()) { null }
        val v = TerminalView(ctx)
        v.session = session
        v.applySettings(TerminalSettings(cursorBlink = false))
        session.profile = profile
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = ByteArray(octets.length) { octets[it].code.toByte() }
        synchronized(session.emulator) { session.emulator.append(b) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** a 4010 address (10-bit [x], [y]) as its four bytes */
    private fun tek(x: Int, y: Int): String =
        "${(0x20 or (y shr 5)).toChar()}${(0x60 or (y and 31)).toChar()}${(0x20 or (x shr 5)).toChar()}${(0x40 or (x and 31)).toChar()}"

    /** a screen in [p]'s own codes: text, its colours or attributes, its graphics */
    private fun sample(p: TerminalProfile): String {
        val e = "\u001b"
        return when (p.id) {
            "c64", "c128", "c128-80", "plus4", "vic20", "pet", "pet80" ->
                // clear, red, HELLO, reverse, a row of graphics
                "\u0093\u001cHELLO \u0012\u0005D-MODEM\u0092\r¦¦¦°®"
            "atari", "atari-intl" -> "}HELLO ÄÍÏÄÅÍ\u009b\u0011\u0012\u0012\u0005\u009b"
            "viewdata" -> "\u000c${e}AHELLO ${e}W\u007f\u007f#5 ${e}FPRESTEL"
            "minitel" -> "\u000c${e}ABONJOUR ${e}F\u000e\u007f\u007f#\u000f MINITEL"
            "tek4014" -> "\u001d${tek(100, 100)}${tek(900, 100)}${tek(500, 700)}${tek(100, 100)}\u001fTEKTRONIX"
            "vt340" -> "Hello VT340 $e[7mInverse$e[0m\r\n" +
                "${e}PpS(M2(H120L50S100))W(I2)P[400,240]C[+100]P[100,300]F(V[+100][+0,+100][-100])$e\\"
            "atari-st", "atari-st-low" -> "${e}EHello ST ${e}b1Red${e}b\u000f ${e}pInverse${e}q \u009e\u00e0"
            "avatar" -> "\u000c\u0016\u0001\u001eAvatar \u0019*\u0008\u0016\u0001\u0004 red \u0016\u0001\u0007 $e[1;32mANSI too$e[0m"
            "vt52" -> "${e}H${e}JHello VT52 ${e}F" + "abcdefg" + "${e}G"
            else -> "$e[2J$e[HHello ${p.label} $e[1mBold$e[0m $e[7mInverse$e[0m $e[4mUnder$e[0m\r\n" +
                "$e(0lqqqqk$e(B $e[31mRed$e[0m"
        }
    }

    /** each profile's screen drawn: more than the background, and (graphics) the picture's own pixels */
    @Test
    fun everyProfile() {
        val dir = File("build/profiles").apply { mkdirs() }
        for (p in TerminalProfiles.all) {
            val bmp = render(p, sample(p))
            File(dir, "${p.id}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val bg = bmp.getPixel(w - 1, h - 1)
            var drawn = 0
            for (y in 0 until h step 2) for (x in 0 until w step 2) if (bmp.getPixel(x, y) != bg) drawn++
            assertTrue("${p.id}: nothing drawn", drawn > 50)
        }
    }
}
