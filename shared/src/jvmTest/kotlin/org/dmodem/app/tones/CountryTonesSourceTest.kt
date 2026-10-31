// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.tones

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared table is D-Modem-RE's as it is now: tools/country_tones.py
 * run on the submodule's dsp/tones.cpp gives the file there is (when the
 * submodule and Python are here).
 */
class CountryTonesSourceTest {
    /** tools/country_tones.py run on D-Modem-RE's tones.cpp gives the committed table (skipped without them or Python) */
    @Test
    fun theTableIsD_ModemREs() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "tools/country_tones.py").exists() } ?: return
        if (!File(root, "external/d-modem-re/dsp/tones.cpp").exists()) return
        val p = runCatching { ProcessBuilder("python3", "tools/country_tones.py").directory(root).start() }.getOrNull() ?: return
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue())
        val have = File(root, "shared/src/commonMain/kotlin/org/dmodem/app/tones/CountryTonesTable.kt").readText()
        assertEquals(have, out, "the table is out of date: run tools/country_tones.py again")
    }
}
