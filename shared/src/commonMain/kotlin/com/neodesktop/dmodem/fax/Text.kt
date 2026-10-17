// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

/** [v] as two hex digits (%02x) */
internal fun hex2(v: Int): String = (v and 0xff).toString(16).padStart(2, '0')

/** ISO 8859-1's octets of the text */
internal fun latin1(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

/** [d] with two decimals, a point between (%.2f in the root locale) */
internal fun fixed2(d: Double): String {
    val c = kotlin.math.round(d * 100).toLong()
    val sign = if (c < 0) "-" else ""
    val a = kotlin.math.abs(c)
    return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}
