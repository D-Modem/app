// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.neodesktop.dmodem.settings.AppPalette
import com.neodesktop.dmodem.settings.Appearance
import com.neodesktop.dmodem.settings.DarkMode
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** dark or not, as [a] has it (the system's by default) */
@Composable
fun isDark(a: Appearance): Boolean = when (a.dark) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

@Composable
fun DModemTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = isDark(appearance)
    val wallpaper = appearance.palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= 31
    var colors = when {
        wallpaper -> {
            val c = LocalContext.current
            if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        }
        else -> Palettes.scheme(appearance.palette.takeIf { it != AppPalette.DYNAMIC } ?: AppPalette.BASELINE, dark)
    }
    if (dark && appearance.black) colors = Palettes.black(colors)
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Material colour schemes made from a hue: each role a tone (CIE L*) of the
 * palette's hue at its chroma (in LCh, cut down to what sRGB can show), as
 * Material's own tonal palettes have them.
 */
object Palettes {

    /** the colour at L* [tone], chroma [c] and hue [h] (degrees) in CIE LCh(ab) */
    fun lch(tone: Double, c: Double, h: Double): Color {
        var lo = 0.0
        var hi = c
        var rgb = rgbOf(tone, c, h)
        if (rgb == null) {
            // the most chroma sRGB has at this tone and hue
            repeat(18) {
                val mid = (lo + hi) / 2
                if (rgbOf(tone, mid, h) != null) lo = mid else hi = mid
            }
            rgb = rgbOf(tone, lo, h)!!
        }
        return Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat())
    }

    private fun rgbOf(l: Double, c: Double, hDeg: Double): DoubleArray? {
        val h = Math.toRadians(hDeg)
        val a = c * cos(h)
        val b = c * sin(h)
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
        val x = 0.95047 * inv(fx)
        val y = if (l > 8) fy * fy * fy else l / (24389.0 / 27)
        val z = 1.08883 * inv(fz)
        val lin = doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
        val eps = 1e-4
        if (lin.any { it < -eps || it > 1 + eps }) return null
        return DoubleArray(3) {
            val v = lin[it].coerceIn(0.0, 1.0)
            if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        }
    }

    private class Tones(val h: Double, val c: Double) {
        operator fun invoke(t: Int) = lch(t.toDouble(), c, h)
    }

    fun scheme(p: AppPalette, dark: Boolean): ColorScheme {
        val a = Tones(p.hue, p.chroma)
        val b = Tones(p.hue, (p.chroma / 3).coerceAtMost(16.0))
        val c = Tones((p.hue + 60) % 360, (p.chroma / 2).coerceAtLeast(if (p.chroma < 10) p.chroma else 16.0))
        val n = Tones(p.hue, (p.chroma / 12).coerceAtMost(4.0))
        val v = Tones(p.hue, (p.chroma / 6).coerceAtMost(8.0))
        val e = Tones(25.0, 84.0)
        return if (!dark) {
            lightColorScheme(
                primary = a(40), onPrimary = a(100), primaryContainer = a(90), onPrimaryContainer = a(10), inversePrimary = a(80),
                secondary = b(40), onSecondary = b(100), secondaryContainer = b(90), onSecondaryContainer = b(10),
                tertiary = c(40), onTertiary = c(100), tertiaryContainer = c(90), onTertiaryContainer = c(10),
                background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
                surfaceVariant = v(90), onSurfaceVariant = v(30), surfaceTint = a(40),
                inverseSurface = n(20), inverseOnSurface = n(95),
                error = e(40), onError = e(100), errorContainer = e(90), onErrorContainer = e(10),
                outline = v(50), outlineVariant = v(80), scrim = Color.Black,
                surfaceBright = n(98), surfaceDim = n(87), surfaceContainerLowest = n(100), surfaceContainerLow = n(96),
                surfaceContainer = n(94), surfaceContainerHigh = n(92), surfaceContainerHighest = n(90),
            )
        } else {
            darkColorScheme(
                primary = a(80), onPrimary = a(20), primaryContainer = a(30), onPrimaryContainer = a(90), inversePrimary = a(40),
                secondary = b(80), onSecondary = b(20), secondaryContainer = b(30), onSecondaryContainer = b(90),
                tertiary = c(80), onTertiary = c(20), tertiaryContainer = c(30), onTertiaryContainer = c(90),
                background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
                surfaceVariant = v(30), onSurfaceVariant = v(80), surfaceTint = a(80),
                inverseSurface = n(90), inverseOnSurface = n(20),
                error = e(80), onError = e(20), errorContainer = e(30), onErrorContainer = e(90),
                outline = v(60), outlineVariant = v(30), scrim = Color.Black,
                surfaceBright = n(24), surfaceDim = n(6), surfaceContainerLowest = n(4), surfaceContainerLow = n(10),
                surfaceContainer = n(12), surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
            )
        }
    }

    /** a dark scheme on black: the surfaces black, their containers a step up each */
    fun black(s: ColorScheme): ColorScheme {
        fun up(by: Float) = Color(by, by, by)
        return s.copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = up(0.04f), surfaceContainer = up(0.06f),
            surfaceContainerHigh = up(0.10f), surfaceContainerHighest = up(0.14f), surfaceBright = up(0.18f),
        )
    }

    /** the swatch for a palette: its primary, as light mode has it */
    fun swatch(p: AppPalette): Color = Tones(p.hue, p.chroma)(40)
}
