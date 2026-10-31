// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

/**
 * What a CONNECT result says beyond the rate ("CONNECT 1200/CVM/LAPM",
 * "CONNECT 56000/V92/LAPM/V44"): the modulation, the error correction,
 * the compression, as the dashboard shows them ("V.cvm · LAPM").
 */
object ConnectInfo {
    private val NAMES = mapOf(
        "CVM" to "V.cvm", "V92" to "V.92", "V90" to "V.90", "V91" to "V.91", "V34" to "V.34",
        "V32B" to "V.32bis", "V32" to "V.32", "V22B" to "V.22bis", "V22" to "V.22", "V23" to "V.23",
        "V21" to "V.21", "B103" to "Bell 103", "B212" to "Bell 212", "LAPM" to "LAPM", "MNP" to "MNP",
        "V44" to "V.44", "V42BIS" to "V.42bis", "MNP5" to "MNP 5",
    )

    /** the last CONNECT line in [console]: its parts after the rate, named; empty when there's none */
    fun label(console: String): String {
        val i = console.lastIndexOf("CONNECT")
        if (i < 0) return ""
        val line = console.substring(i).substringBefore('\n').substringBefore('\r')
        return line.removePrefix("CONNECT").trim().split('/').drop(1)
            .mapNotNull { NAMES[it.trim().uppercase()] }
            .joinToString(" · ")
    }
}
