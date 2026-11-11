// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The character sets a remote system may speak: UTF-8, IBM PC's code page
 * 437 (the BBSes' ANSI art) and ISO 8859-1. Decoding is a stream (a UTF-8
 * sequence may be split between two reads); C0 controls and DEL stay
 * controls in every set.
 */
enum class TermCharset(val label: String) {
    UTF8("UTF-8"),
    CP437("CP437 (IBM PC, BBS)"),
    LATIN1("ISO 8859-1");

    /** text to the remote: its octets in this set (what it can't have as '?') */
    fun encode(s: String): ByteArray = when (this) {
        UTF8 -> s.encodeToByteArray()
        LATIN1 -> ByteArray(s.length) { i -> s[i].code.let { if (it < 256) it.toByte() else '?'.code.toByte() } }
        CP437 -> {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAtIndex(i)
                i += charCountOf(cp)
                out.add(Cp437.encode(cp).toByte())
            }
            out.toByteArray()
        }
    }
}

/** IBM PC code page 437 */
object Cp437 {
    /** 0x80..0xff */
    private const val HIGH =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
            "áíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
            "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
            "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

    private val reverse: Map<Int, Int> by lazy {
        HIGH.mapIndexed { i, c -> c.code to (0x80 + i) }.toMap()
    }

    /** a CP437 octet as its code point */
    fun decode(b: Int): Int = if (b < 0x80) b else HIGH[b - 0x80].code

    /** a code point as its CP437 octet: '?' if it has none */
    fun encode(cp: Int): Int = if (cp < 0x80) cp else reverse[cp] ?: '?'.code
}

/** octets to code points, in a set, across reads */
class TermDecoder(var charset: TermCharset) {
    fun interface Sink {
        /** a code point decoded */
        fun codePoint(cp: Int)
    }

    private var need = 0
    private var cp = 0
    private var min = 0

    /** a UTF-8 sequence half read dropped */
    fun reset() {
        need = 0
    }

    /** the code points of [data] (0 until [length]) to [out] */
    fun decode(data: ByteArray, length: Int, out: Sink) {
        for (i in 0 until length) feed(data[i].toInt() and 0xff, out)
    }

    /** one octet decoded in the charset, its code point (if it completes one) to [out] */
    fun feed(b: Int, out: Sink) {
        when (charset) {
            TermCharset.LATIN1 -> out.codePoint(b)
            TermCharset.CP437 -> out.codePoint(Cp437.decode(b))
            TermCharset.UTF8 -> utf8(b, out)
        }
    }

    /** one UTF-8 octet: a code point to [out] when its sequence completes, U+FFFD for a bad or overlong one */
    private fun utf8(b: Int, out: Sink) {
        if (need > 0) {
            if (b and 0xc0 == 0x80) {
                cp = (cp shl 6) or (b and 0x3f)
                if (--need == 0) {
                    out.codePoint(if (cp < min || cp > 0x10ffff || cp in 0xd800..0xdfff) 0xfffd else cp)
                }
                return
            }
            // a sequence cut short: its replacement, then this octet anew
            need = 0
            out.codePoint(0xfffd)
        }
        when {
            b < 0x80 -> out.codePoint(b)
            b and 0xe0 == 0xc0 -> { cp = b and 0x1f; need = 1; min = 0x80 }
            b and 0xf0 == 0xe0 -> { cp = b and 0x0f; need = 2; min = 0x800 }
            b and 0xf8 == 0xf0 -> { cp = b and 0x07; need = 3; min = 0x10000 }
            else -> out.codePoint(0xfffd)
        }
    }
}

/**
 * A character set a G0-G3 slot can hold: what its 0x21..0x7e stand for
 * (a 96-character set's 0x20..0x7f). [designators] are what follow the
 * slot's intermediate to designate it (ESC ( B for ASCII, ESC ( % 5 for
 * DEC Supplemental; a 96-character set's after ESC - . or /); a set with
 * none is chosen another way (a VT52's ESC F). [level] is the VT level
 * that has it (the national sets the VT220's NRCS, Portuguese the VT320's).
 */
enum class GSet(val designators: List<String>, val is96: Boolean = false, val level: Int = 1, private val table: String? = null) {
    ASCII(listOf("B")),
    /** DEC Special Graphics: line drawing in 0x5f..0x7e */
    DEC_GRAPHICS(listOf("0")),
    /** the United Kingdom set: ASCII with £ for # */
    UK(listOf("A"), table = "#£"),
    /** the VT52's graphics: fractions, scan lines, subscripts in 0x5e..0x7e (its ESC F) */
    VT52_GRAPHICS(emptyList()),
    /** DEC Supplemental Graphics (the VT220's GR): Latin-1's letters with Œ, Ÿ, œ, ÿ and ¤ where DEC put them */
    DEC_SUPPLEMENTAL(listOf("%5", "<"), level = 2),
    /** DEC Technical (VT320): mathematics, Greek, arrows, and the pieces of big brackets, integrals and sums */
    DEC_TECHNICAL(listOf(">"), level = 3),
    /** ISO Latin-1's upper half (a 96-character set) */
    LATIN1(listOf("A"), is96 = true, level = 3),
    DUTCH(listOf("4"), level = 2, table = "#£@¾[ĳ\\½]|{¨|ƒ}¼~´"),
    FINNISH(listOf("C", "5"), level = 2, table = "[Ä\\Ö]Å^Ü`é{ä|ö}å~ü"),
    FRENCH(listOf("R", "f"), level = 2, table = "#£@à[°\\ç]§{é|ù}è~¨"),
    FRENCH_CANADIAN(listOf("Q", "9"), level = 2, table = "@à[â\\ç]ê^î`ô{é|ù}è~û"),
    GERMAN(listOf("K"), level = 2, table = "@§[Ä\\Ö]Ü{ä|ö}ü~ß"),
    ITALIAN(listOf("Y"), level = 2, table = "#£@§[°\\ç]é`ù{à|ò}è~ì"),
    NORWEGIAN_DANISH(listOf("E", "6", "`"), level = 2, table = "@Ä[Æ\\Ø]Å^Ü`ä{æ|ø}å~ü"),
    SPANISH(listOf("Z"), level = 2, table = "#£@§[¡\\Ñ]¿{°|ñ}ç"),
    SWEDISH(listOf("H", "7"), level = 2, table = "@É[Ä\\Ö]Å^Ü`é{ä|ö}å~ü"),
    SWISS(listOf("="), level = 2, table = "#ù@à[é\\ç]ê^î_è`ô{ä|ö}ü~û"),
    PORTUGUESE(listOf("%6"), level = 3, table = "[Ã\\Ç]Õ{ã|ç}õ"),
    /** the soft set the host loaded (DECDLD): designated by the designator it gave, mapped by [CharsetShifting] */
    SOFT(emptyList(), level = 2);

    /** the national set's replacements: each ASCII character and what it is here */
    private val replaced: Map<Int, Int> by lazy {
        val t = table ?: return@lazy emptyMap()
        (t.indices step 2).associate { t[it].code to t[it + 1].code }
    }

    /** the designator a slot is given this set by (its first) */
    val designator: String? get() = designators.firstOrNull()

    /** [cp] (0x20..0x7f, as GL has it) as this set has it */
    fun map(cp: Int): Int = when (this) {
        ASCII -> cp
        DEC_GRAPHICS -> TerminalEmulator.decGraphics(cp)
        VT52_GRAPHICS -> if (cp in 0x5e..0x7e) VT52[cp - 0x5e] else cp
        DEC_SUPPLEMENTAL -> if (cp in 0x21..0x7e) SUPPLEMENTAL_ODD[cp] ?: (cp + 0x80) else cp
        DEC_TECHNICAL -> if (cp in 0x21..0x7e) TECHNICAL[cp - 0x21].code else cp
        LATIN1 -> if (cp in 0x20..0x7f) cp + 0x80 else cp
        else -> replaced[cp] ?: cp
    }

    companion object {
        /** the set of the size [is96] that [designator] (ESC ( [designator]) names, or null for one not known */
        fun byDesignator(designator: String, is96: Boolean = false): GSet? =
            entries.firstOrNull { it.is96 == is96 && designator in it.designators }

        /** the set [final] designates as a 94-character set (ESC ( [final]), or null for one not known */
        fun byFinal(final: Int): GSet? = byDesignator(final.toChar().toString())

        /** where DEC Supplemental isn't Latin-1: ¤ at 0x28, Œ Ÿ œ ÿ, the codes it left empty (shown blank) */
        private val SUPPLEMENTAL_ODD: Map<Int, Int> = mapOf(
            0x28 to 0xa4, 0x57 to 0x152, 0x5d to 0x178, 0x77 to 0x153, 0x7d to 0xff,
        ) + listOf(0x24, 0x26, 0x2c, 0x2d, 0x2e, 0x2f, 0x34, 0x38, 0x3e, 0x50, 0x5e, 0x70, 0x7e).associateWith { 0x20 }

        /**
         * DEC Technical, 0x21..0x7e (as xterm draws it: the brackets', the
         * integral's and the sum's pieces as Unicode's nearest; the codes DEC
         * left empty blank).
         */
        private const val TECHNICAL =
            "⎷┌─⌠⌡│⎡⎣⎤⎦⎛⎝⎞⎠⎨⎬" +
            "⎲⎳╲╱⌝⌟⟩    ≤≠≥∫∴" +
            "∝∞÷Δ∇ΦΓ∼≃Θ×Λ⇔⇒≡Π" +
            "Ψ Σ  √ΩΞΥ⊂⊃∩∪∧∨¬" +
            "αβχδεφγηιθκλ ν∂π" +
            "ψρστ ƒωξυζ←↑→↓"

        /**
         * The VT52's graphics, 0x5e..0x7e (DECscope User's Manual EK-VT5X-OP-001,
         * appendix B): 3 blank, a solid rectangle, the fraction numerators 1/ 3/
         * 5/ 7/ (shown as ⅟ ³ ⁵ ⁷), degree, plus-minus, right arrow, ellipsis,
         * divide, down arrow, bars at scan lines 0 (top) to 7, subscripts 0-9,
         * the paragraph sign.
         */
        private val VT52 = intArrayOf(
            0x20, 0x20, 0x20, 0x2588, 0x215f, 0xb3, 0x2075, 0x2077,
            0xb0, 0xb1, 0x2192, 0x2026, 0xf7, 0x2193,
            0x2594, 0x1fb76, 0x1fb77, 0x1fb78, 0x1fb79, 0x1fb7a, 0x1fb7b, 0x2581,
            0x2080, 0x2081, 0x2082, 0x2083, 0x2084, 0x2085, 0x2086, 0x2087, 0x2088, 0x2089,
            0xb6,
        )
    }
}
