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
    data class Send(val key: Key, override val label: String, val repeat: Boolean = false, val shift: Boolean = false) : BarKey()

    /** text typed */
    data class Type(val text: String, override val label: String = text) : BarKey()

    /** Ctrl, down for the next key */
    data object Ctrl : BarKey() {
        override val label get() = "CTRL"
    }

    /** Alt, down for the next key (a Commodore's C= key) */
    data class Alt(override val label: String = "ALT") : BarKey()

    /** octets sent as they are (Latin-1, one character each): a machine's own codes ([color]: the swatch it shows, ARGB) */
    data class Octets(val octets: String, override val label: String, val color: Int? = null) : BarKey()

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
                BarKey.Send(Key.TAB, "TAB"), BarKey.Ctrl, BarKey.Alt(), BarKey.Send(Key.LEFT, "←", repeat = true),
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

    /** an LK201's top row past F5: F6-F20 (F15 Help, F16 Do), Find and Select */
    val LK201_FUNCTION = KeyBarPage(
        "F6+",
        listOf(
            (5 until 13).map { fn(it) },
            listOf(fn(13), BarKey.Send(Key.F15, "HELP"), BarKey.Send(Key.F16, "DO")) + (16 until 20).map { fn(it) } +
                BarKey.Send(Key.FIND, "FIND"),
            listOf(BarKey.Send(Key.SELECT, "SEL"), BarKey.Send(Key.INSERT, "INS"), BarKey.Send(Key.DELETE, "REM"), BarKey.NextPage),
        ),
    )

    /** F6-F20 with Shift: the keys the host loaded (DECUDK) */
    val USER_DEFINED = KeyBarPage(
        "UDK",
        listOf(
            (5 until 13).map { fn(it, shift = true) },
            (13 until 20).map { fn(it, shift = true) } + BarKey.NextPage,
        ),
    )

    /** a Commodore's: RUN/STOP, CLR/HOME, INST/DEL, RVS ON/OFF, C=, the cursor keys, its symbols */
    val COMMODORE_MAIN = KeyBarPage(
        "C=",
        listOf(
            listOf(
                BarKey.Octets("\u0003", "STOP"), BarKey.Octets("\u0093", "CLR"), BarKey.Send(Key.HOME, "HOME"),
                BarKey.Send(Key.UP, "↑", repeat = true), BarKey.Send(Key.INSERT, "INST"), BarKey.Octets("\u0012", "RVS"),
                BarKey.Octets("\u0092", "OFF"), BarKey.Type("£"),
            ),
            listOf(
                BarKey.Ctrl, BarKey.Alt("C="), BarKey.Send(Key.LEFT, "←", repeat = true), BarKey.Send(Key.DOWN, "↓", repeat = true),
                BarKey.Send(Key.RIGHT, "→", repeat = true), BarKey.Type("↑", "^"), BarKey.Type("←", "_"), BarKey.NextPage,
            ),
        ),
    )

    /** F1-F8 (F2, F4, F6, F8 the shifted ones) and DEL */
    val COMMODORE_FUNCTION = KeyBarPage(
        "F1",
        listOf(
            listOf(fn(0), fn(2), fn(4), fn(6), BarKey.Send(Key.BACKSPACE, "DEL", repeat = true)),
            listOf(fn(1), fn(3), fn(5), fn(7), BarKey.NextPage),
        ),
    )

    /** the colours, CTRL 1-8 and C= 1-8, each key showing its colour on [palette] (16 ARGB, the machine's order) */
    fun commodoreColors(palette: IntArray, ctrlCodes: List<Int>, cbmCodes: List<Int>): KeyBarPage {
        /** the key for colour code [code], colour [i] */
        fun key(code: Int, i: Int) = BarKey.Octets(code.toChar().toString(), COMMODORE_COLOR_NAMES[i], palette[i])
        return KeyBarPage(
            "COL",
            listOf(
                ctrlCodes.mapIndexed { i, c -> key(c, i) },
                cbmCodes.mapIndexed { i, c -> key(c, 8 + i) } + BarKey.NextPage,
            ),
        )
    }

    /** the 16 colours' short names, in the machines' order */
    private val COMMODORE_COLOR_NAMES = listOf(
        "BLK", "WHT", "RED", "CYN", "PUR", "GRN", "BLU", "YEL",
        "ORN", "BRN", "LRD", "DGY", "GRY", "LGN", "LBL", "LGY",
    )

    /** an Atari's: ESC, CLEAR, INSERT and DELETE (character, and with SHIFT the line), BACK S, TAB, the inverse key, CTRL for the graphics */
    val ATARI = KeyBarPage(
        "ATR",
        listOf(
            listOf(
                BarKey.Send(Key.ESCAPE, "ESC"), BarKey.Octets("\u007d", "CLR"), BarKey.Send(Key.INSERT, "INS"),
                BarKey.Send(Key.UP, "↑", repeat = true), BarKey.Send(Key.DELETE, "DEL", repeat = true),
                BarKey.Octets("\u009d", "INS LN"), BarKey.Octets("\u009c", "DEL LN"),
            ),
            listOf(
                BarKey.Send(Key.TAB, "TAB"), BarKey.Ctrl, BarKey.Alt("INV"), BarKey.Send(Key.LEFT, "←", repeat = true),
                BarKey.Send(Key.DOWN, "↓", repeat = true), BarKey.Send(Key.RIGHT, "→", repeat = true),
                BarKey.Send(Key.BACKSPACE, "BK S", repeat = true),
            ),
        ),
    )

    /** the bar most terminals have: the main keys and the function keys */
    val STANDARD = listOf(MAIN, FUNCTION)

    /** a DEC terminal's: the standard ones and its keypad */
    val DEC = STANDARD + DEC_KEYPAD

    /** a VT220's and later: a DEC terminal's, the LK201's other function keys, and the user-defined ones */
    val LK201 = DEC + LK201_FUNCTION + USER_DEFINED

    /** F[n + 1] (with Shift: ⇧ before its name) */
    private fun fn(n: Int, shift: Boolean = false) =
        BarKey.Send(Key.entries[Key.F1.ordinal + n], (if (shift) "⇧" else "") + "F${n + 1}", shift = shift)

    /** the keypad's [n] */
    private fun kp(n: Int) = BarKey.Send(Key.entries[Key.KP_0.ordinal + n], "$n")

    /** the name the next-page key on page [i] of [pages] shows */
    fun nextLabel(pages: List<KeyBarPage>, i: Int): String = pages[(i + 1) % pages.size].name
}
