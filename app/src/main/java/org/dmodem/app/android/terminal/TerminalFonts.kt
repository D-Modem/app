// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * The terminal's fonts: the system's monospace, or one of those in the
 * app's assets (assets/fonts, their licences in assets/fonts/licenses).
 * A variable font gets its regular and bold weights from the one file;
 * one without a bold face is made bold by the paint.
 */
enum class TerminalFont(
    val label: String,
    /** the file in assets, null for the system's monospace */
    val asset: String?,
    /** a variable font (a 'wght' axis): its regular and bold weights */
    val variable: Boolean = false,
    /** its licence in assets/fonts/licenses */
    val license: String? = null,
    /** has programming ligatures (-> != ==) */
    val ligatures: Boolean = false,
) {
    SYSTEM("System monospace", null),
    CASCADIA_CODE("Cascadia Code", "fonts/CascadiaCode.ttf", variable = true, license = "cascadiacode-OFL.txt", ligatures = true),
    SAUCE_CODE_PRO("Sauce Code Pro (Nerd Font)", "fonts/SauceCodeProNerdFontMono.ttf", license = "saucecodepro-nerdfont-OFL.txt"),
    SOURCE_CODE_PRO("Source Code Pro", "fonts/SourceCodePro.ttf", variable = true, license = "sourcecodepro-OFL.txt"),
    ROBOTO_MONO("Roboto Mono", "fonts/RobotoMono.ttf", variable = true, license = "robotomono-OFL.txt"),
    JETBRAINS_MONO("JetBrains Mono", "fonts/JetBrainsMono.ttf", variable = true, license = "jetbrainsmono-OFL.txt", ligatures = true),
    FIRA_CODE("Fira Code", "fonts/FiraCode.ttf", variable = true, license = "firacode-OFL.txt", ligatures = true),
    OPEN_DYSLEXIC("OpenDyslexic Mono", "fonts/OpenDyslexicMono.ttf", license = "opendyslexic-OFL.txt"),
    INCONSOLATA("Inconsolata", "fonts/Inconsolata.ttf", variable = true, license = "inconsolata-OFL.txt"),
    DROID_SANS_MONO("Droid Sans Mono", "fonts/DroidSansMono.ttf", license = "droidsansmono-Apache-2.0.txt");

    /** a bold face of its own (not the paint's) */
    val hasBold: Boolean get() = variable || asset == null

    companion object {
        /** the font of that enum name, or [SYSTEM] if none is */
        fun byName(name: String?): TerminalFont = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** The fonts' typefaces, made once each. */
object FontCache {
    private val cache = HashMap<Pair<TerminalFont, Boolean>, Typeface>()

    /** [font]'s typeface, bold or not; made the first time */
    @Synchronized
    fun typeface(context: Context, font: TerminalFont, bold: Boolean): Typeface =
        cache.getOrPut(font to bold) { load(context.assets, font, bold) }

    /** [font]'s typeface from its asset (a variable one at weight 700 or 400), the monospace one if it has none or it fails */
    private fun load(assets: AssetManager, font: TerminalFont, bold: Boolean): Typeface {
        val path = font.asset ?: return Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        return try {
            val b = Typeface.Builder(assets, path)
            // (some fonts' default instance is light: Source Code Pro's is 200, Fira Code's 300)
            if (font.variable) b.setFontVariationSettings("'wght' ${if (bold) 700 else 400}")
            b.build() ?: Typeface.MONOSPACE
        } catch (_: RuntimeException) {
            Typeface.MONOSPACE
        }
    }

    /** the font's licence text, or null */
    fun license(context: Context, font: TerminalFont): String? = font.license?.let {
        try {
            context.assets.open("fonts/licenses/$it").bufferedReader().use { r -> r.readText() }
        } catch (_: java.io.IOException) {
            null
        }
    }
}
