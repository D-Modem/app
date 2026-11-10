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
 * A 94-character set a G0-G3 slot can hold: what its 0x21..0x7e stand for.
 * [final] is the character that designates it (ESC ( B for ASCII); a set
 * with none is chosen another way (a VT52's ESC F).
 */
enum class GSet(val final: Char?) {
    ASCII('B'),
    /** DEC Special Graphics: line drawing in 0x5f..0x7e */
    DEC_GRAPHICS('0'),
    /** the United Kingdom set: ASCII with £ for # */
    UK('A'),
    /** the VT52's graphics: fractions, scan lines, subscripts in 0x5e..0x7e (its ESC F) */
    VT52_GRAPHICS(null);

    /** [cp] as this set has it */
    fun map(cp: Int): Int = when (this) {
        ASCII -> cp
        DEC_GRAPHICS -> TerminalEmulator.decGraphics(cp)
        UK -> if (cp == '#'.code) '£'.code else cp
        VT52_GRAPHICS -> if (cp in 0x5e..0x7e) VT52[cp - 0x5e] else cp
    }

    companion object {
        /** the set [final] designates (ESC ( [final]), or null for one not known */
        fun byFinal(final: Int): GSet? = entries.firstOrNull { it.final?.code == final }

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
