// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A Minitel (France Télécom's Teletel, CEPT-2) as MiniPavi's 3615
 * services expect it: 40x25, row 0 its status row; text (G0) and mosaics
 * (G1, by SO) with the accents' SS2; its ESC attributes (colours, flash,
 * sizes drawn single, inverse, conceal; the background and the mosaics'
 * separation taking effect at the next space or mosaic, as CEPT-2's zones
 * do); the CSI cursor and erase commands; the PRO1-PRO3 protocol
 * commands (it answers who it is); the 80-column Mixte mode; and the
 * Téléinformatique mode, a VT100. Its keys: Envoi, Retour, Suite,
 * Annulation, Sommaire, Guide, Correction, Répétition, Connexion/Fin.
 */
open class MinitelProfile : VideotexProfile() {
    override val id get() = "minitel"
    override val label get() = "Minitel"
    override val term get() = "minitel"
    override val telnetType get() = "MINITEL"
    override val rows get() = 25

    override val keyBar: List<KeyBarPage> get() = listOf(KeyBars.MINITEL, KeyBars.MAIN)

    /** a [MinitelInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = MinitelInterpreter(t, this)
}

/** The Minitel's videotex mode, and its Téléinformatique mode handed to a VT100 */
open class MinitelInterpreter(t: TerminalEmulator, private val m: MinitelProfile) : VideotexInterpreter(t, m) {
    private var bg = 0
    /** the background asked for, taken at the next delimiter (a space, a mosaic) */
    private var nextBg = 0
    private var flash = false
    private var conceal = false
    /** the mosaics separated (G1's underline), from the next delimiter on */
    private var separated = false
    private var nextSeparated = false
    /** G1 (the mosaics) in use, by SO */
    private var mosaic = false
    /** the screen scrolls (PRO2 ROULEAU), rather than the cursor going back to row 1 */
    private var scrolling = false
    /** Mixte mode: 80 columns of text */
    private var mixte = false
    /** the last character shown, for REP */
    private var last = ' '.code

    // what's half read: an ESC sequence, US's address, REP's count, SS2's accent, a CSI's parameters
    private var pending = ByteArray(0)
    private var want = 0
    private var expecting = Expect.NONE
    private enum class Expect { NONE, ESC, PRO, US, REP, SS2, ACCENT, CSI, SEP }
    private var accent = 0
    private val csi = StringBuilder()

    /** Téléinformatique mode: a VT100 reads what comes, until ESC [ ? { */
    private var teleinfo: VtInterpreter? = null
    /** how much of the way out of Téléinformatique mode has come */
    private var exitMatch = 0

    init {
        at(0, 1)
    }

    /** the row the cursor moves in from: row 1 (row 0 is the status row) */
    override val firstRow: Int get() = 1

    /** as at the start: videotex mode, white on black, G0, page mode, the cursor at row 1 */
    override fun reset() {
        super.reset()
        teleinfo = null
        mixte = false
        scrolling = false
        attributes()
        expecting = Expect.NONE
        at(0, 1)
    }

    /** the attributes as a row starts: white on black, steady, revealed, G0, contiguous */
    private fun attributes() {
        color = 7
        bg = 0
        nextBg = 0
        flash = false
        conceal = false
        reverse = false
        separated = false
        nextSeparated = false
        mosaic = false
        t.style = look()
    }

    /** what's written looks like: the colour, the zone's background, flash, inverse, conceal */
    override fun look(): Long = Style.of(
        color, bg,
        (if (reverse) Style.INVERSE else 0) or (if (flash) Style.BLINK else 0) or (if (conceal) Style.INVISIBLE else 0),
    )

    /** an octet: to the VT100 in Téléinformatique mode, else the rest of a sequence, a control or a character */
    override fun process(cp: Int) {
        val b = cp and 0x7f
        teleinfo?.let { vt ->
            if (leavesTeleinfo(b)) return
            vt.process(b)
            return
        }
        if (expecting != Expect.NONE) {
            sequence(b)
            return
        }
        if (control(b)) return
        if (b >= 0x20) show(b)
    }

    /** whether [b] ends ESC [ ? { (Téléinformatique mode left: the videotex screen back) */
    private fun leavesTeleinfo(b: Int): Boolean {
        val exit = "\u001b[?{"
        exitMatch = if (b == exit[exitMatch].code) exitMatch + 1 else if (b == 0x1b) 1 else 0
        if (exitMatch < exit.length) return false
        exitMatch = 0
        teleinfo = null
        t.requestedColumns = m.columns
        t.requestedRows = m.rows
        clear()
        attributes()
        at(0, 1)
        return true
    }

    /** the Minitel's controls: true if [b] was one */
    override fun control(b: Int): Boolean {
        when (b) {
            0x00 -> {}
            // (DEL: G1's full block, nothing in G0)
            0x7f -> if (mosaic) show(b)
            0x07 -> bell()
            0x08 -> back()
            0x09 -> forward()
            0x0a -> lineDown()
            0x0b -> lineUp()
            0x0c -> {
                clear()
                attributes()
                at(0, 1)
            }
            0x0d -> at(0, t.cursorY)
            0x0e -> if (!mixte) { mosaic = true; separated = false; nextSeparated = false }
            0x0f -> mosaic = false
            0x11 -> t.cursorVisible = true
            0x14 -> t.cursorVisible = false
            0x12 -> expecting = Expect.REP
            0x13 -> expecting = Expect.SEP
            0x18 -> {
                t.style = Style.erased(look())
                t.eraseLine(0)
                t.style = look()
            }
            0x19 -> expecting = Expect.SS2
            0x1b -> expecting = Expect.ESC
            0x1e -> {
                attributes()
                at(0, 1)
            }
            0x1f -> { pending = ByteArray(0); expecting = Expect.US }
            else -> return b < 0x20
        }
        return true
    }

    /** octet [b] of the sequence under way */
    private fun sequence(b: Int) {
        val e = expecting
        expecting = Expect.NONE
        when (e) {
            Expect.ESC -> esc(b)
            Expect.PRO -> {
                pending += b.toByte()
                if (pending.size < want) expecting = Expect.PRO else protocol(pending)
            }
            Expect.US -> {
                pending += b.toByte()
                if (pending.size < 2) expecting = Expect.US else address(pending[0].toInt(), pending[1].toInt())
            }
            Expect.REP -> repeat((b - 0x40).coerceIn(0, 63)) { show(last) }
            Expect.SEP -> {}
            Expect.SS2 -> supplementary(b)
            Expect.ACCENT -> show(accented(b, accent))
            Expect.CSI -> {
                if (b in 0x30..0x3f) {
                    csi.append(b.toChar())
                    expecting = Expect.CSI
                } else {
                    command(b)
                }
            }
            Expect.NONE -> {}
        }
    }

    /** US's address: row and column as 0x40 + n, or as two digits each (US 3/x) */
    private fun address(a: Int, b: Int) {
        val (row, col) = if (a in 0x30..0x32) {
            ((a - 0x30) * 10 + (b - 0x30)) to 1
        } else {
            (a - 0x40) to (b - 0x40)
        }
        attributes()
        at((col - 1).coerceIn(0, t.cols - 1), row.coerceIn(0, t.rows - 1))
    }

    /** the code after ESC: an attribute (0x40-0x5f), CSI, a protocol command, the cursor's position asked */
    private fun esc(b: Int) {
        when (b) {
            in 0x40..0x47 -> color = b - 0x40
            in 0x50..0x57 -> nextBg = b - 0x50
            0x48 -> flash = true
            0x49 -> flash = false
            // (sizes: drawn single)
            0x4c, 0x4d, 0x4e, 0x4f -> {}
            0x58 -> conceal = true
            0x5f -> conceal = false
            0x59 -> nextSeparated = false
            0x5a -> nextSeparated = true
            0x5c -> reverse = false
            0x5d -> reverse = true
            0x5b -> { csi.setLength(0); expecting = Expect.CSI }
            0x39, 0x3a, 0x3b -> { pending = ByteArray(0); want = b - 0x38; expecting = Expect.PRO }
            // the cursor's place asked: US, its row and column
            0x61 -> t.reply("\u001f${(0x40 + t.cursorY).toChar()}${(0x41 + t.cursorX).toChar()}")
        }
        t.style = look()
    }

    /** a protocol command (PRO1-PRO3)'s [p]: who it is (ENQROM), scrolling, Mixte and Téléinformatique modes; the rest taken */
    private fun protocol(p: ByteArray) {
        val a = p[0].toInt()
        val b = p.getOrElse(1) { 0 }.toInt()
        when {
            p.size == 1 && a == 0x7b -> t.reply(IDENTITY)
            p.size == 2 && a == 0x69 && b == 0x43 -> scrolling = true
            p.size == 2 && a == 0x6a && b == 0x43 -> scrolling = false
            p.size == 2 && a == 0x32 && b == 0x7d -> {
                mixte = true
                mosaic = false
                t.requestedColumns = 80
            }
            p.size == 2 && a == 0x32 && b == 0x7e -> {
                mixte = false
                t.requestedColumns = m.columns
            }
            p.size == 2 && a == 0x31 && b == 0x7d -> {
                // Téléinformatique: an 80x24 VT100
                t.requestedColumns = 80
                t.requestedRows = 24
                teleinfo = VtInterpreter(t, TELEINFO)
                exitMatch = 0
                t.style = Style.NORMAL
            }
        }
    }

    /** a CSI's command [final] with the parameters gathered: the cursor's moves, erasing, insert and delete */
    private fun command(final: Int) {
        val ps = csi.toString().split(';').map { it.toIntOrNull() ?: 0 }
        /** parameter [i], [d] when missing or 0 */
        fun p(i: Int, d: Int) = ps.getOrNull(i)?.takeIf { it != 0 } ?: d
        t.style = Style.erased(look())
        when (final.toChar()) {
            'A' -> at(t.cursorX, (t.cursorY - p(0, 1)).coerceAtLeast(firstRow))
            'B' -> at(t.cursorX, t.cursorY + p(0, 1))
            'C' -> at(t.cursorX + p(0, 1), t.cursorY)
            'D' -> at(t.cursorX - p(0, 1), t.cursorY)
            'H' -> at(p(1, 1) - 1, p(0, 1))
            'J' -> t.eraseDisplay(ps.getOrNull(0) ?: 0)
            'K' -> t.eraseLine(ps.getOrNull(0) ?: 0)
            'P' -> t.deleteChars(p(0, 1))
            '@' -> t.insertChars(p(0, 1))
            'L' -> t.insertLines(p(0, 1))
            'M' -> t.deleteLines(p(0, 1))
            'h' -> if (ps.getOrNull(0) == 4) t.insertMode = true
            'l' -> if (ps.getOrNull(0) == 4) t.insertMode = false
        }
        t.style = look()
    }

    /** SS2's G2 character [b]: an accent to put on the next letter, or a symbol */
    private fun supplementary(b: Int) {
        if (b in 0x41..0x4b) {
            accent = b
            expecting = Expect.ACCENT
            return
        }
        G2[b]?.let { show(it) }
    }

    /** letter [b] with accent [a] (SS2 A grave, B acute, C circumflex, H diaeresis, K cedilla), or the letter */
    private fun accented(b: Int, a: Int): Int {
        if (a == 0x4b) return if (b == 'c'.code) 'ç'.code else if (b == 'C'.code) 'Ç'.code else b
        val i = ACCENTED_BASE.indexOf(b.toChar())
        val letters = TYPED_ACCENTS.firstOrNull { it.first == a }?.second
        return if (i < 0 || letters == null) b else letters[i].code
    }

    /** a character shown: a mosaic in G1 (0x40-0x5f its letters), else G0's; a space or a mosaic takes the zone's attributes */
    private fun show(b: Int) {
        val isMosaic = mosaic && b < 0x80 && (b < 0x40 || b >= 0x60)
        if (isMosaic || b == ' '.code) {
            bg = nextBg
            separated = nextSeparated
        }
        val c = if (isMosaic) Mosaic.code(Mosaic.bits(b), separated) else b
        last = b
        t.style = look()
        t.print(c)
        if (t.pendingWrap) {
            t.carriageReturn()
            lineDown()
        }
    }

    /** the next row (from the last: row 1 again, or the screen scrolled in ROULEAU) */
    override fun lineDown() {
        if (t.cursorY >= t.rows - 1 && scrolling) {
            val x = t.cursorX
            t.index()
            at(x, t.cursorY)
        } else {
            super.lineDown()
        }
    }

    /** (characters are [show]n, not looked up) */
    override fun charOf(b: Int): Int = b

    /** Enter as Envoi, Backspace as Correction (SEP and the key's code); the rest as a VT100's (the cursor keys' CSI) */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        if (teleinfo != null) return null
        return when (k) {
            Key.ENTER -> byteArrayOf(0x13, 0x41)
            Key.BACKSPACE -> byteArrayOf(0x13, 0x47)
            else -> null
        }
    }

    /** an accented letter typed as SS2, its accent and the letter; the rest as ASCII */
    override fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? {
        if (teleinfo != null || ctrl || alt) return null
        if (cp < 0x80) return null
        for ((accent, letters) in TYPED_ACCENTS) {
            val i = letters.indexOf(cp.toChar())
            if (i >= 0) return byteArrayOf(0x19, accent.toByte(), ACCENTED_BASE[i % ACCENTED_BASE.length].code.toByte())
        }
        if (cp == 'ç'.code) return byteArrayOf(0x19, 0x4b, 'c'.code.toByte())
        G2.entries.firstOrNull { it.value == cp }?.let { return byteArrayOf(0x19, it.key.toByte()) }
        return null
    }

    companion object {
        /** ENQROM's answer: a Telic Minitel 2 */
        private const val IDENTITY = "\u0001Cv;\u0004"

        /** the VT100 Téléinformatique mode is */
        private val TELEINFO = Vt100Profile()

        /** the letters an accent goes on, lower case then upper */
        private const val ACCENTED_BASE = "aeuioAEUIO"

        /** the accents as typed: SS2's accent and the accented letters (in [ACCENTED_BASE]'s order) */
        private val TYPED_ACCENTS = listOf(
            0x41 to "àèùìòÀÈÙÌÒ", 0x42 to "áéúíóÁÉÚÍÓ", 0x43 to "âêûîôÂÊÛÎÔ", 0x48 to "äëüïöÄËÜÏÖ",
        )

        /** G2's symbols, by SS2's code */
        private val G2 = mapOf(
            0x23 to '£'.code, 0x24 to '$'.code, 0x26 to '#'.code, 0x27 to '§'.code,
            0x2c to '←'.code, 0x2d to '↑'.code, 0x2e to '→'.code, 0x2f to '↓'.code,
            0x30 to '°'.code, 0x31 to '±'.code, 0x38 to '÷'.code,
            0x3c to '¼'.code, 0x3d to '½'.code, 0x3e to '¾'.code,
            0x6a to 'Œ'.code, 0x7a to 'œ'.code, 0x7b to 'ß'.code,
        )
    }
}
