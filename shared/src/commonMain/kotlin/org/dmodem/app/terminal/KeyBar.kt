// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The keys a phone's keyboard hasn't got, as pages of rows the apps draw
 * above it: each profile has its own (a DEC terminal adds its keypad, a
 * Commodore its keys), described once here for Android and iOS alike.
 */
sealed class BarKey {
    /** what the key shows */
    abstract val label: String

    /** a key that isn't a character; [repeat]: held down, it repeats */
    data class Send(val key: Key, override val label: String, val repeat: Boolean = false) : BarKey()

    /** text typed */
    data class Type(val text: String, override val label: String = text) : BarKey()

    /** Ctrl, down for the next key */
    data object Ctrl : BarKey() {
        override val label get() = "CTRL"
    }

    /** Alt, down for the next key */
    data object Alt : BarKey() {
        override val label get() = "ALT"
    }

    /** to the next page (from the last, the first): shown as that page's name */
    data object NextPage : BarKey() {
        override val label get() = "»"
    }
}

/** A page of the bar: its name (on the key that brings it) and its rows. */
class KeyBarPage(val name: String, val rows: List<List<BarKey>>)

/** The pages there are, and the profiles' sets of them. */
object KeyBars {
    /** Esc, Tab, Ctrl, Alt, the cursor keys, Home/End and the page keys, a few symbols */
    val MAIN = KeyBarPage(
        "ABC",
        listOf(
            listOf(
                BarKey.Send(Key.ESCAPE, "ESC"), BarKey.Type("/"), BarKey.Type("-"), BarKey.Send(Key.HOME, "HOME"),
                BarKey.Send(Key.UP, "↑", repeat = true), BarKey.Send(Key.END, "END"), BarKey.Send(Key.PAGE_UP, "PGUP"),
                BarKey.Type("|"),
            ),
            listOf(
                BarKey.Send(Key.TAB, "TAB"), BarKey.Ctrl, BarKey.Alt, BarKey.Send(Key.LEFT, "←", repeat = true),
                BarKey.Send(Key.DOWN, "↓", repeat = true), BarKey.Send(Key.RIGHT, "→", repeat = true),
                BarKey.Send(Key.PAGE_DOWN, "PGDN"), BarKey.NextPage,
            ),
        ),
    )

    /** F1-F12, Insert, Delete, ~ */
    val FUNCTION = KeyBarPage(
        "Fn",
        listOf(
            (0 until 6).map { fn(it) } + listOf(BarKey.Send(Key.INSERT, "INS"), BarKey.Type("~")),
            (6 until 12).map { fn(it) } + listOf(BarKey.Send(Key.DELETE, "DEL"), BarKey.NextPage),
        ),
    )

    /** the DEC keypad: PF1-PF4, its digits, minus, comma, period and Enter (numeric or application mode, as the remote set) */
    val DEC_KEYPAD = KeyBarPage(
        "KP",
        listOf(
            listOf(
                BarKey.Send(Key.PF1, "PF1"), BarKey.Send(Key.PF2, "PF2"), BarKey.Send(Key.PF3, "PF3"),
                BarKey.Send(Key.PF4, "PF4"), BarKey.Send(Key.KP_MINUS, "-"), BarKey.Send(Key.KP_COMMA, ","),
            ),
            listOf(
                kp(7), kp(8), kp(9), kp(4), kp(5), kp(6),
            ),
            listOf(
                kp(1), kp(2), kp(3), kp(0), BarKey.Send(Key.KP_PERIOD, "."), BarKey.Send(Key.KP_ENTER, "ENT"),
                BarKey.NextPage,
            ),
        ),
    )

    /** the bar most terminals have: the main keys and the function keys */
    val STANDARD = listOf(MAIN, FUNCTION)

    /** a DEC terminal's: the standard ones and its keypad */
    val DEC = STANDARD + DEC_KEYPAD

    /** F[n + 1] */
    private fun fn(n: Int) = BarKey.Send(Key.entries[Key.F1.ordinal + n], "F${n + 1}")

    /** the keypad's [n] */
    private fun kp(n: Int) = BarKey.Send(Key.entries[Key.KP_0.ordinal + n], "$n")

    /** the name the next-page key on page [i] of [pages] shows */
    fun nextLabel(pages: List<KeyBarPage>, i: Int): String = pages[(i + 1) % pages.size].name
}
