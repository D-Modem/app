// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A kind of terminal the emulator can be: what it calls itself (to SSH,
 * Telnet and the programs that ask), which sequences it knows, and the
 * interpreter that reads them. Each kind is a class that takes the one
 * it grew out of and changes only what differs (a VT220 is a VT102 with
 * more), so behaviour lives in one place; the instances are in
 * [TerminalProfiles].
 */
abstract class TerminalProfile {
    /** kept in settings and directory entries */
    abstract val id: String
    /** shown in the pickers */
    abstract val label: String
    /** TERM, for SSH and the local shells */
    abstract val term: String
    /** what Telnet's TERMINAL-TYPE answers */
    open val telnetType: String get() = term.uppercase()
    /** the character set it speaks, unless the entry or the user chose one (null: theirs) */
    open val charset: TermCharset? get() = null
    /** ESC[2J homes the cursor, as ANSI.SYS did (BBS screens expect it) */
    open val homeOnClear: Boolean get() = false

    /** the bitmap glyphs its cells are drawn with (its machine's character ROM, as Unscii draws it); null: the view's font */
    open val glyphs: BitmapFont? get() = null

    /** a capture's file extension, where its machine's files had their own (a Commodore's .seq); null: the app's */
    open val captureExtension: String? get() = null

    /** the keys the apps' bar offers, page by page */
    open val keyBar: List<KeyBarPage> get() = KeyBars.STANDARD

    /** an interpreter of this terminal's sequences, on [t]'s screen */
    abstract fun interpreter(t: TerminalEmulator): TerminalInterpreter

    /** (the label) */
    override fun toString(): String = label
}

/** What a DEC-style terminal has beyond its level's text: chosen by each profile. */
enum class VtFeature {
    /** SGR's colours (8, 16, 256, 24-bit) */
    COLOR,
    /** sixel images */
    SIXEL,
    /** a printer port (VT102 and later): printer controller mode, auto print, print screen (MC) */
    PRINTER,
    /** xterm's own: mouse and focus reports, bracketed paste, the alternate screen, OSC titles, colours, clipboard and links, XTVERSION, window reports */
    XTERM,
    /** Tek mode (DECSET 38): a Tektronix 4014 in the terminal */
    TEK,
    /** ReGIS graphics (DCS p) */
    REGIS,
}

/**
 * A terminal in DEC's VT line (and those modelled on it, as xterm): ANSI
 * X3.64 sequences read by [VtInterpreter], at a conformance level, with
 * its own answers to the device attribute requests.
 */
abstract class DecProfile : TerminalProfile() {
    /** DA1's answer (CSI c) */
    abstract val primaryDa: String
    /** DA2's answer (CSI > c); null: not answered */
    open val secondaryDa: String? get() = null
    /** the VT level: 1 VT100 and VT102, 2 VT220, 3 VT320, 4 VT420, 5 VT5xx */
    open val level: Int get() = 1
    /** what it has beyond its level's text */
    open val features: Set<VtFeature> get() = emptySet()

    /** the standard keys and the DEC keypad */
    override val keyBar: List<KeyBarPage> get() = KeyBars.DEC

    /** whether it has [f] */
    fun has(f: VtFeature): Boolean = f in features

    /** a [VtInterpreter] (subclasses for levels that read more) */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = VtInterpreter(t, this)
}

/** DEC VT52: its own escape sequences (no ANSI), its graphics set, no attributes */
open class Vt52Profile : TerminalProfile() {
    override val id get() = "vt52"
    override val label get() = "DEC VT52"
    override val term get() = "vt52"
    override val telnetType get() = "DEC-VT52"

    /** the standard keys and the keypad (the VT52's has PF1-PF3 and its digits) */
    override val keyBar: List<KeyBarPage> get() = KeyBars.DEC

    /** a [Vt52Interpreter] with the VT52's own graphics, answering ESC Z as a VT52 without a copier */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter =
        Vt52Interpreter(t, GSet.VT52_GRAPHICS, "\u001b/K")
}

/** DEC VT100, with the Advanced Video Option */
open class Vt100Profile : DecProfile() {
    override val id get() = "vt100"
    override val label get() = "DEC VT100"
    override val term get() = "vt100"
    override val telnetType get() = "DEC-VT100"
    override val primaryDa get() = "\u001b[?1;2c"
}

/** DEC VT102: a VT100 with character and line editing (and its printer port) */
open class Vt102Profile : Vt100Profile() {
    override val id get() = "vt102"
    override val label get() = "DEC VT102"
    override val term get() = "vt102"
    override val telnetType get() = "DEC-VT102"
    override val primaryDa get() = "\u001b[?6c"
    override val features: Set<VtFeature> get() = super.features + VtFeature.PRINTER
}

/** DEC VT220: level 2, 8-bit controls, more character sets */
open class Vt220Profile : Vt102Profile() {
    override val id get() = "vt220"
    override val label get() = "DEC VT220"
    override val term get() = "vt220"
    override val telnetType get() = "DEC-VT220"
    override val level get() = 2
    // 132 columns, printer, selective erase, user-defined keys, NRCS, technical set
    override val primaryDa get() = "\u001b[?62;1;2;6;7;8;9c"
    // a VT220, firmware 1.0
    override val secondaryDa: String? get() = "\u001b[>1;10;0c"
    /** a DEC terminal's keys, the LK201's F6-F20 and the user-defined ones */
    override val keyBar: List<KeyBarPage> get() = KeyBars.LK201
}

/** DEC VT320: level 3, DEC Technical, the requests a VT320 answers (DECRQSS, DECRQPSR, DECRQUPSS) */
open class Vt320Profile : Vt220Profile() {
    override val id get() = "vt320"
    override val label get() = "DEC VT320"
    override val term get() = "vt320"
    override val telnetType get() = "DEC-VT320"
    override val level get() = 3
    // 132 columns, printer, selective erase, user-defined keys, NRCS, technical set
    override val primaryDa get() = "\u001b[?63;1;2;6;7;8;9c"
    // a VT320, firmware 1.0
    override val secondaryDa: String? get() = "\u001b[>24;10;0c"
}

/** DEC VT340: a VT320 with graphics, sixel and ReGIS in 16 colour registers they share, and Tek mode */
open class Vt340Profile : Vt320Profile() {
    override val id get() = "vt340"
    override val label get() = "DEC VT340"
    override val term get() = "vt340"
    override val telnetType get() = "DEC-VT340"
    // 132 columns, printer, ReGIS, sixel, selective erase, user-defined keys, NRCS, technical set
    override val primaryDa get() = "\u001b[?63;1;2;3;4;6;7;8;9c"
    // a VT340, firmware 1.0
    override val secondaryDa: String? get() = "\u001b[>19;10;0c"
    override val features: Set<VtFeature> get() = super.features + VtFeature.SIXEL + VtFeature.REGIS + VtFeature.TEK
}

/** DEC VT420: level 4, left and right margins, rectangular areas, six pages */
open class Vt420Profile : Vt320Profile() {
    override val id get() = "vt420"
    override val label get() = "DEC VT420"
    override val term get() = "vt420"
    override val telnetType get() = "DEC-VT420"
    override val level get() = 4
    // 132 columns, printer, selective erase, user-defined keys, NRCS, technical set, locator, text macros, rectangles
    override val primaryDa get() = "\u001b[?64;1;2;6;7;8;9;15;18;21;28c"
    // a VT420, firmware 1.0
    override val secondaryDa: String? get() = "\u001b[>41;10;0c"

    /** a [Vt420Interpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = Vt420Interpreter(t, this)
}

/** DEC VT510: level 5, the display extent and the PC keyboard's modes */
open class Vt510Profile : Vt420Profile() {
    override val id get() = "vt510"
    override val label get() = "DEC VT510"
    override val term get() = "vt510"
    override val telnetType get() = "DEC-VT510"
    override val level get() = 5
    override val primaryDa get() = "\u001b[?65;1;2;6;7;8;9;15;18;21;28c"
    override val secondaryDa: String? get() = "\u001b[>61;10;0c"
}

/** DEC VT520: a VT510 with more sessions (one here) */
open class Vt520Profile : Vt510Profile() {
    override val id get() = "vt520"
    override val label get() = "DEC VT520"
    override val term get() = "vt520"
    override val telnetType get() = "DEC-VT520"
    override val secondaryDa: String? get() = "\u001b[>64;10;0c"
}

/** DEC VT525: a VT520 in colour (SGR's, DECAC's, the colour table) */
open class Vt525Profile : Vt520Profile() {
    override val id get() = "vt525"
    override val label get() = "DEC VT525"
    override val term get() = "vt525"
    override val telnetType get() = "DEC-VT525"
    // a VT520's, and colour (22)
    override val primaryDa get() = "\u001b[?65;1;2;6;7;8;9;15;18;21;22;28c"
    override val secondaryDa: String? get() = "\u001b[>65;10;0c"
    override val features: Set<VtFeature> get() = super.features + VtFeature.COLOR
}

/** xterm: a VT220 with colours, sixel and its own extensions */
open class XtermProfile : Vt220Profile() {
    override val id get() = "xterm"
    override val label get() = "xterm"
    override val term get() = "xterm-256color"
    override val telnetType get() = "XTERM-256COLOR"
    /** a DEC terminal's keys (a PC keyboard's F-keys, not an LK201's) */
    override val keyBar: List<KeyBarPage> get() = KeyBars.DEC
    // a VT220 with sixel graphics (4) and ANSI colour (22)
    override val primaryDa get() = "\u001b[?62;4;22c"
    // xterm, patch 390
    override val secondaryDa: String? get() = "\u001b[>41;390;0c"
    override val features: Set<VtFeature> get() = super.features + VtFeature.COLOR + VtFeature.SIXEL + VtFeature.XTERM + VtFeature.TEK
    // (as before profiles: BBSes reached as xterm count on ANSI.SYS's clear)
    override val homeOnClear get() = true
}

/** A PC's BBS terminal: ANSI.SYS's sequences in CP437, as SyncTERM and NetRunner are */
open class AnsiBbsProfile : XtermProfile() {
    override val id get() = "ansi-bbs"
    override val label get() = "ANSI-BBS (PC)"
    override val term get() = "ansi"
    override val telnetType get() = "ANSI"
    override val charset: TermCharset? get() = TermCharset.CP437
    /** a VGA's 8x16 code page 437 */
    override val glyphs: BitmapFont? get() = LegacyCharsets.VGA

    /** an [AnsiBbsInterpreter]: CTerm's additions */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = AnsiBbsInterpreter(t, this)
}

/** The profiles there are, by id. */
object TerminalProfiles {
    val VT52: TerminalProfile = Vt52Profile()
    val VT100: TerminalProfile = Vt100Profile()
    val VT102: TerminalProfile = Vt102Profile()
    val VT220: TerminalProfile = Vt220Profile()
    val VT320: TerminalProfile = Vt320Profile()
    val VT340: TerminalProfile = Vt340Profile()
    val VT420: TerminalProfile = Vt420Profile()
    val VIEWDATA: TerminalProfile = ViewdataProfile()
    val MINITEL: TerminalProfile = MinitelProfile()
    val VT510: TerminalProfile = Vt510Profile()
    val VT520: TerminalProfile = Vt520Profile()
    val VT525: TerminalProfile = Vt525Profile()
    val XTERM: TerminalProfile = XtermProfile()
    val ANSI_BBS: TerminalProfile = AnsiBbsProfile()
    val C64: TerminalProfile = C64Profile()
    val C128: TerminalProfile = C128Profile()
    val C128_80: TerminalProfile = C128VdcProfile()
    val PLUS4: TerminalProfile = Plus4Profile()
    val VIC20: TerminalProfile = Vic20Profile()
    val PET: TerminalProfile = PetProfile()
    val PET80: TerminalProfile = Pet80Profile()
    val ATARI: TerminalProfile = AtariProfile()
    val ATARI_INTL: TerminalProfile = AtariInternationalProfile()
    val TEK4014: TerminalProfile = Tek4014Profile()
    val ATARI_ST: TerminalProfile = AtariStProfile()
    val ATARI_ST_LOW: TerminalProfile = AtariStLowProfile()

    /** the default: what the app was before profiles */
    val DEFAULT: TerminalProfile get() = XTERM

    /** in the pickers' order */
    val all: List<TerminalProfile> = listOf(XTERM, ANSI_BBS, VT52, VT100, VT102, VT220, VT320, VT340, VT420, VT510, VT520, VT525, C64, C128, C128_80, PLUS4, VIC20, PET, PET80, ATARI, ATARI_INTL, ATARI_ST, ATARI_ST_LOW, VIEWDATA, MINITEL, TEK4014)

    /** the profile [id] names, or null for none or one not known */
    fun byId(id: String?): TerminalProfile? = all.firstOrNull { it.id == id }
}
