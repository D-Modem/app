// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A Commodore 8-bit as a terminal (PETSCII): the C64 and the machines
 * around it, each a subclass changing only what its machine had (a
 * VIC-20's narrow screen and fewer colours, a C128's escape sequences, its
 * 80-column chip's palette and underline, a PET's green screen and no
 * colours, a Plus/4's flash). They share [PetsciiInterpreter].
 */
abstract class CommodoreProfile : TerminalProfile(), ByteMachine {
    override val term get() = "petscii"
    override val telnetType get() = "PETSCII"
    /** its character ROM's (a C64's) */
    override val glyphs: BitmapFont? get() = MachineFonts.C64
    override val captureExtension: String? get() = ".seq"

    override val columns get() = 40
    override val rows get() = 25
    /** light blue on black, as the terminal programs start (the C64 itself starts light blue on blue) */
    override val defaultColor get() = 14
    override val background get() = 0

    /** the character sets: upper case and graphics, lower and upper case (256 code points each, 0 none) */
    open val upperSet: IntArray get() = LegacyCharsets.C64_UPPER
    open val lowerSet: IntArray get() = LegacyCharsets.C64_LOWER

    /** CTRL 1-8's colour codes (colours 0-7) */
    open val ctrlColors: List<Int> get() = listOf(0x90, 0x05, 0x1c, 0x9f, 0x9c, 0x1e, 0x1f, 0x9e)
    /** C= 1-8's (colours 8-15), none on a machine without them */
    open val cbmColors: List<Int> get() = listOf(0x81, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b)

    /** whether ESC starts one of the C128's and Plus/4's editor sequences */
    open val escapes: Boolean get() = false
    /** the codes that turn an attribute on and off ([Style] bits): a C128's underline and flash */
    open val attrCodes: Map<Int, Pair<Int, Boolean>> get() = emptyMap()
    /** whether TAB (09) and LF (0A) are the C128's tab and line feed, not the C64's case lock and nothing */
    open val tabAndLineFeed: Boolean get() = false

    /** the colour codes and the colours they set */
    val colorCodes: Map<Int, Int> by lazy {
        (ctrlColors.mapIndexed { i, c -> c to i } + cbmColors.mapIndexed { i, c -> c to 8 + i }).toMap()
    }

    override val keyBar: List<KeyBarPage> by lazy {
        listOf(KeyBars.COMMODORE_MAIN, KeyBars.COMMODORE_FUNCTION) +
            (if (ctrlColors.isEmpty()) emptyList() else listOf(KeyBars.commodoreColors(palette, ctrlColors, cbmColors)))
    }

    /** a [PetsciiInterpreter] for this machine */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = PetsciiInterpreter(t, this)
}

/** The palettes (ARGB, in the VIC-II's colour order unless said otherwise). */
object CommodorePalettes {
    /** Philip Timmermann's "Pepto" measurements of the VIC-II */
    val PEPTO = argb(
        0x000000, 0xffffff, 0x68372b, 0x70a4b2, 0x6f3d86, 0x588d43, 0x352879, 0xb8c76f,
        0x6f4f25, 0x433900, 0x9a6759, 0x444444, 0x6c6c6c, 0x9ad284, 0x6c5eb5, 0x959595,
    )

    /** Pepto's later "Colodore" */
    val COLODORE = argb(
        0x000000, 0xffffff, 0x813338, 0x75cec8, 0x8e3c97, 0x56ac4d, 0x2e2c9b, 0xedf171,
        0x8e5029, 0x553800, 0xc46c71, 0x4a4a4a, 0x7b7b7b, 0xa9ff9f, 0x706deb, 0xb2b2b2,
    )

    /** the VIC-20's VIC chip: eight colours, then the eight lighter ones only its background and border had */
    val VIC20 = argb(
        0x000000, 0xffffff, 0xb61f21, 0x4df0ff, 0xb43fff, 0x44e237, 0x1a34ff, 0xdcd71b,
        0xca5400, 0xe9b072, 0xe79293, 0x9af7fd, 0xe09fff, 0x8fe493, 0x8290ff, 0xe5de85,
    )

    /** the C128's VDC (RGBI), each VIC-II colour as the 80-column screen shows it */
    val VDC: IntArray = run {
        val rgbi = intArrayOf(
            0x000000, 0x555555, 0x0000aa, 0x5555ff, 0x00aa00, 0x55ff55, 0x00aaaa, 0x55ffff,
            0xaa0000, 0xff5555, 0xaa00aa, 0xff55ff, 0xaa5500, 0xffff55, 0xaaaaaa, 0xffffff,
        )
        // (the C128 editor's table: the VIC-II's colour numbers to the VDC's)
        val vicToVdc = intArrayOf(0, 15, 8, 7, 11, 4, 2, 13, 10, 12, 9, 1, 6, 5, 3, 14)
        IntArray(16) { 0xff000000.toInt() or rgbi[vicToVdc[it]] }
    }

    /** the Plus/4's TED: the colour codes' sixteen colours, near what its default luminances give */
    val TED = argb(
        0x000000, 0xffffff, 0x9c3e3e, 0x5ab5b5, 0x9f4ba8, 0x5aa33e, 0x5250c4, 0xb5b54a,
        0xa36a2b, 0x7d5a1b, 0x85a33e, 0xc7637a, 0x3ea383, 0x6a8cd9, 0x4f5fb3, 0x7ec26b,
    )

    /** a PET's phosphor: dark, then its green */
    val PET_GREEN = argb(0x000000, 0x41ff00)

    /** 0xRRGGBB values as opaque ARGB */
    private fun argb(vararg rgb: Int): IntArray = IntArray(rgb.size) { 0xff000000.toInt() or rgb[it] }
}

/** Commodore 64: 40x25, 16 colours, the screen editor's control codes */
open class C64Profile : CommodoreProfile() {
    override val id get() = "c64"
    override val label get() = "Commodore 64"
    override val palette: IntArray get() = CommodorePalettes.PEPTO
}

/** Commodore 128 in 40 columns: a C64 with the editor's ESC sequences, TAB, LF and the bell */
open class C128Profile : C64Profile() {
    override val id get() = "c128"
    override val label get() = "Commodore 128 (40 col.)"
    override val escapes get() = true
    override val tabAndLineFeed get() = true
    /** its own character ROM's */
    override val glyphs: BitmapFont? get() = MachineFonts.C128
}

/** Commodore 128 in 80 columns (the VDC): RGBI colours, underline and flash */
open class C128VdcProfile : C128Profile() {
    override val id get() = "c128-80"
    override val label get() = "Commodore 128 (80 col.)"
    override val columns get() = 80
    override val palette: IntArray get() = CommodorePalettes.VDC
    override val attrCodes: Map<Int, Pair<Int, Boolean>> get() = mapOf(
        0x02 to (Style.UNDERLINE to true), 0x82 to (Style.UNDERLINE to false),
        0x0f to (Style.BLINK to true), 0x8f to (Style.BLINK to false),
    )
}

/** Commodore Plus/4 and C16: a C64's codes with the TED's colours, flash, and the C128's ESC sequences */
open class Plus4Profile : C64Profile() {
    override val id get() = "plus4"
    override val label get() = "Commodore Plus/4"
    override val palette: IntArray get() = CommodorePalettes.TED
    override val escapes get() = true
    override val attrCodes: Map<Int, Pair<Int, Boolean>> get() = mapOf(
        0x82 to (Style.BLINK to true), 0x84 to (Style.BLINK to false),
    )
}

/** VIC-20: 22x23, eight colours for characters, its own sets */
open class Vic20Profile : C64Profile() {
    override val id get() = "vic20"
    override val label get() = "Commodore VIC-20"
    override val columns get() = 22
    override val rows get() = 23
    override val palette: IntArray get() = CommodorePalettes.VIC20
    override val defaultColor get() = 1
    override val upperSet: IntArray get() = LegacyCharsets.VIC_UPPER
    override val lowerSet: IntArray get() = LegacyCharsets.VIC_LOWER
    override val cbmColors: List<Int> get() = emptyList()
}

/** Commodore PET (40 columns): green on black, no colours, its own sets */
open class PetProfile : CommodoreProfile() {
    override val id get() = "pet"
    override val label get() = "Commodore PET (40 col.)"
    override val palette: IntArray get() = CommodorePalettes.PET_GREEN
    override val defaultColor get() = 1
    override val upperSet: IntArray get() = LegacyCharsets.PET_UPPER
    override val lowerSet: IntArray get() = LegacyCharsets.PET_LOWER
    override val ctrlColors: List<Int> get() = emptyList()
    override val cbmColors: List<Int> get() = emptyList()
}

/** Commodore PET 8032: a PET with 80 columns */
open class Pet80Profile : PetProfile() {
    override val id get() = "pet80"
    override val label get() = "Commodore PET (80 col.)"
    override val columns get() = 80
}

/**
 * The PETSCII screen editor, as CCGMS and Novaterm have it: the colour
 * codes, the cursor keys (11 91 1D 9D), HOME, CLR, DEL, INST, RVS on and
 * off, the two character sets (0E lower case, 8E upper), RETURN and its
 * shifted one ending reverse, the bell; on a C128 or Plus/4 its ESC
 * sequences (window, insert and delete line, scrolling, erasing). Quote
 * mode is the editor's, not a terminal's, so there's none.
 */
open class PetsciiInterpreter(t: TerminalEmulator, private val m: CommodoreProfile) : ByteControlInterpreter(t, m) {
    /** the lower and upper case set (0E), not the upper case and graphics one (8E) */
    var lowerCase = false
        private set
    /** the octet after an ESC, on a machine with the sequences */
    private var esc = false

    private val set: IntArray get() = if (lowerCase) m.lowerSet else m.upperSet

    /** as at the start: the upper case and graphics set, no ESC pending */
    override fun reset() {
        super.reset()
        lowerCase = false
        esc = false
    }

    /** octet [b] in the set in use */
    override fun charOf(b: Int): Int = set[b]

    /** the colour, attribute, cursor, editing and set codes, and ESC sequences on the machines with them */
    override fun control(b: Int): Boolean {
        if (esc) {
            esc = false
            escape(b)
            return true
        }
        m.colorCodes[b]?.let { colorTo(it); return true }
        m.attrCodes[b]?.let { (a, on) -> attrTo(a, on); return true }
        when (b) {
            0x07 -> bell()
            0x09 -> if (m.tabAndLineFeed) tab()
            0x0a -> if (m.tabAndLineFeed) down()
            0x0d, 0x8d -> {
                newLine()
                reverseTo(false)
            }
            0x0e -> lowerCase = true
            0x8e -> lowerCase = false
            0x11 -> down()
            0x91 -> up()
            0x1d -> right()
            0x9d -> left()
            0x12 -> reverseTo(true)
            0x92 -> reverseTo(false)
            0x13 -> home()
            0x93 -> clear()
            0x14 -> deleteBack()
            0x94 -> insertBlank()
            0x1b -> esc = m.escapes
            // (the rest of 00-1F and 80-9F: nothing; the case lock, the function keys' codes)
            else -> return b < 0x20 || b in 0x80..0x9f
        }
        return true
    }

    /** ESC [c]: the C128 editor's (and the Plus/4's) */
    private fun escape(c: Int) {
        when (c.toChar()) {
            '@' -> t.eraseDisplay(0)
            'A' -> t.insertMode = true
            'C' -> t.insertMode = false
            'D' -> t.deleteLines(1)
            'I' -> t.insertLines(1)
            'J' -> t.moveCursor(0, t.cursorY)
            'K' -> t.moveCursor(endOfLine(), t.cursorY)
            'O' -> {
                t.insertMode = false
                reverseTo(false)
            }
            'P' -> t.eraseLine(1)
            'Q' -> t.eraseLine(0)
            'T' -> t.setRegion(t.cursorY, t.bottom)
            'B' -> t.setRegion(t.top, t.cursorY)
            'V' -> t.scrollUp(t.top, t.bottom)
            'W' -> t.scrollDown(t.top, t.bottom)
            'N' -> t.reverseVideo = false
            'R' -> t.reverseVideo = true
            'Y' -> resetTabs()
            'Z' -> resetTabs(every = false)
        }
    }

    /** the column after the line's last character (its last column if it's full) */
    private fun endOfLine(): Int {
        val l = t.line(t.cursorY)
        var x = t.cols - 1
        while (x > 0 && (l.chars[x] == 0 || l.chars[x] == ' '.code)) x--
        return (x + 1).coerceAtMost(t.cols - 1)
    }

    /** a Commodore's keys: the cursor keys, HOME and CLR, INST and DEL, RETURN, RUN/STOP, F1-F8 (none for the others) */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        val b = when (k) {
            Key.UP -> 0x91
            Key.DOWN -> 0x11
            Key.LEFT -> 0x9d
            Key.RIGHT -> 0x1d
            Key.HOME -> if (shift) 0x93 else 0x13
            Key.INSERT -> 0x94
            Key.BACKSPACE, Key.DELETE -> if (shift) 0x94 else 0x14
            Key.ENTER, Key.KP_ENTER -> if (shift) 0x8d else 0x0d
            Key.ESCAPE -> 0x03
            Key.TAB -> 0x09
            Key.F1 -> 0x85
            Key.F3 -> 0x86
            Key.F5 -> 0x87
            Key.F7 -> 0x88
            Key.F2 -> 0x89
            Key.F4 -> 0x8a
            Key.F6 -> 0x8b
            Key.F8 -> 0x8c
            in Key.KP_0..Key.KP_9 -> '0'.code + (k.ordinal - Key.KP_0.ordinal)
            Key.KP_MINUS -> '-'.code
            Key.KP_COMMA -> ','.code
            Key.KP_PERIOD -> '.'.code
            else -> return ByteArray(0)
        }
        return byteArrayOf(b.toByte())
    }

    /** a character typed or pasted as its PETSCII code (nothing for none) */
    override fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? {
        val b = code(cp, alt, ctrl) ?: return ByteArray(0)
        return byteArrayOf(b.toByte())
    }

    /** what the keyboard sends for [cp] with C= ([alt]) or CTRL: a colour, a graphic, a control code, the set's character */
    private fun code(cp: Int, alt: Boolean, ctrl: Boolean): Int? {
        val upper = if (cp in 'a'.code..'z'.code) cp - 32 else cp
        if (ctrl) {
            when (cp) {
                in '1'.code..'8'.code -> return m.ctrlColors.getOrNull(cp - '1'.code)
                '9'.code -> return 0x12
                '0'.code -> return 0x92
            }
            if (upper in 'A'.code..'Z'.code) return upper - '@'.code
        }
        if (alt) {
            if (cp in '1'.code..'8'.code) return m.cbmColors.getOrNull(cp - '1'.code)
            if (upper in 'A'.code..'Z'.code) return CBM_GRAPHICS[upper - 'A'.code]
        }
        return when {
            cp == '\r'.code || cp == '\n'.code -> 0x0d
            cp == 0x08 || cp == 0x7f -> 0x14
            cp == 0x1b -> 0x03
            // letters: unshifted in the graphics set (what its keyboard sends), cased in the other
            cp in 'a'.code..'z'.code -> cp - 32
            cp in 'A'.code..'Z'.code -> if (lowerCase) cp + 0x80 else cp
            cp == '^'.code -> 0x5e
            cp == '_'.code -> 0x5f
            // (a shifted or C= graphic's code over its copy at 60-7F and E0-FE)
            else -> codeIn(set, cp, 0x20..0x5f) ?: codeIn(set, cp, 0xa0..0xdf) ?: codeIn(set, cp) ?: '?'.code
        }
    }

    companion object {
        /** C= and A-Z: the graphics on the keys' left fronts */
        private val CBM_GRAPHICS = intArrayOf(
            0xb0, 0xbf, 0xbc, 0xac, 0xb1, 0xbb, 0xa5, 0xb4, 0xa2, 0xb5, 0xa1, 0xb6, 0xa7,
            0xaa, 0xb9, 0xaf, 0xab, 0xb2, 0xae, 0xa3, 0xb8, 0xbe, 0xb3, 0xbd, 0xb7, 0xad,
        )
    }
}
