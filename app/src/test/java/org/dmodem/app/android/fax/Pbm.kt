// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.fax

import org.dmodem.app.fax.FaxPage
import java.io.File

/** fax pages as PBM files (P4), for the tests with other fax programs */
object Pbm {
    /** [p] as a PBM file */
    fun write(p: FaxPage, f: File) {
        f.outputStream().use { o ->
            o.write("P4\n${p.width} ${p.rows}\n".toByteArray())
            o.write(p.bits)
        }
    }

    /** a PBM file's page */
    fun read(f: File, fine: Boolean): FaxPage {
        val d = f.readBytes()
        var i = 0
        val fields = ArrayList<String>()
        while (fields.size < 3) {
            while (d[i].toInt().toChar().isWhitespace()) i++
            if (d[i].toInt().toChar() == '#') { while (d[i].toInt() != '\n'.code) i++; continue }
            val s = i
            while (!d[i].toInt().toChar().isWhitespace()) i++
            fields.add(String(d, s, i - s))
        }
        i++
        val w = fields[1].toInt()
        val h = fields[2].toInt()
        val p = FaxPage(w, h, fine)
        System.arraycopy(d, i, p.bits, 0, minOf(p.bits.size, d.size - i))
        return p
    }
}
