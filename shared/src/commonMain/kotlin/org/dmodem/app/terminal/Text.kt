// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * What the JVM's String and Character have for text that Kotlin's common
 * library leaves out (the iOS side has none of the JVM): code points
 * across surrogate pairs, ISO 8859-1's octets.
 */

/** the code point at [i] (a surrogate pair's whole, an unpaired one as it is) */
internal fun String.codePointAtIndex(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xd800) shl 10) + (this[i + 1].code - 0xdc00)
    }
    return c.code
}

/** the chars [cp] takes: 2 above the BMP */
internal fun charCountOf(cp: Int): Int = if (cp >= 0x10000) 2 else 1

/** [cp] as a String */
internal fun codePointString(cp: Int): String {
    if (cp < 0x10000) return cp.toChar().toString()
    val v = cp - 0x10000
    return charArrayOf((0xd800 + (v shr 10)).toChar(), (0xdc00 + (v and 0x3ff)).toChar()).concatToString()
}

/** ISO 8859-1's octets of the text (above 0xff: '?', as the JVM's encoder has it) */
internal fun String.latin1(): ByteArray = ByteArray(length) { i -> this[i].code.let { if (it < 256) it.toByte() else '?'.code.toByte() } }

/** [cp] appended (a surrogate pair above the BMP) */
internal fun StringBuilder.appendCp(cp: Int): StringBuilder =
    if (cp < 0x10000) append(cp.toChar()) else append(codePointString(cp))
