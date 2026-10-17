// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Fax pages as a PDF, as fax software has them: each page an image in
 * the fax's own coding (CCITTFaxDecode, T.4 MH with its EOLs), the size
 * the fax had it (215 mm across, its rows at 196 or 98 to the inch).
 * Small, and the pels exactly those that came.
 */
object FaxPdf {
    fun write(pages: List<FaxPage>, out: OutputStream) {
        val o = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: ByteArray) {
            offsets.add(o.size())
            o.write("${offsets.size} 0 obj\n".toByteArray())
            o.write(body)
            o.write("\nendobj\n".toByteArray())
        }
        fun obj(s: String) = obj(s.toByteArray(Charsets.ISO_8859_1))
        fun stream(dict: String, data: ByteArray) =
            obj("<< $dict /Length ${data.size} >>\nstream\n".toByteArray() + data + "\nendstream".toByteArray())

        o.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        // 1: the catalogue, 2: the page tree; then each page's three: the page, its contents, its image
        val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        for ((i, p) in pages.withIndex()) {
            val page = 3 + i * 3
            val w = "%.2f".format(java.util.Locale.ROOT, p.width / 204.0 * 72)
            val h = "%.2f".format(java.util.Locale.ROOT, p.rows.coerceAtLeast(1).toDouble() / (if (p.fine) 196 else 98) * 72)
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /Im0 ${page + 2} 0 R >> >> /Contents ${page + 1} 0 R >>")
            stream("", "q $w 0 0 $h 0 0 cm /Im0 Do Q".toByteArray())
            // (the fax's bits, the first in the bottom bit, the other way round: PDF's first in the top bit)
            val data = T4.encode(p).also { d -> for (k in d.indices) d[k] = reverse(d[k]) }
            stream(
                "/Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.rows} /ColorSpace /DeviceGray /BitsPerComponent 1 " +
                    "/Filter /CCITTFaxDecode /DecodeParms << /K 0 /Columns ${p.width} /Rows ${p.rows} /EndOfLine true >>",
                data,
            )
        }
        val xref = o.size()
        o.write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (off in offsets) o.write("%010d 00000 n \n".format(off).toByteArray())
        o.write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        out.write(o.toByteArray())
    }

    private fun reverse(b: Byte): Byte {
        var c = b.toInt() and 0xff
        c = (c and 0xf0 shr 4) or (c and 0x0f shl 4)
        c = (c and 0xcc shr 2) or (c and 0x33 shl 2)
        c = (c and 0xaa shr 1) or (c and 0x55 shl 1)
        return c.toByte()
    }
}
