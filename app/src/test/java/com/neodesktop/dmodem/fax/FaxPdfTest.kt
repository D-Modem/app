// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.asSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Received pages as a PDF: poppler (pdfimages, pdftoppm) reads them back, every pel as it came. */
class FaxPdfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        /** a PDF's pages' images, as poppler's pdfimages takes them out (PBM, 1 black) */
        fun images(pdf: File, dir: File): List<FaxPage> {
            val p = ProcessBuilder("pdfimages", pdf.path, File(dir, "img").path).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            assertEquals(out, 0, p.waitFor())
            return dir.listFiles { f -> f.name.startsWith("img-") }!!.sortedBy { it.name }.map { readPbm(it) }
        }

        fun readPbm(f: File): FaxPage {
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
            assertEquals("P4", fields[0])
            val page = FaxPage(fields[1].toInt(), fields[2].toInt(), true)
            System.arraycopy(d, i, page.bits, 0, minOf(page.bits.size, d.size - i))
            return page
        }
    }

    @Test
    fun pagesReadBackExactly() {
        assumeTrue("poppler-utils isn't installed", File("/usr/bin/pdfimages").canExecute())
        val pages = listOf(T4Test.testPage(true, rows = 700, seed = 1), T4Test.testPage(false, rows = 350, seed = 2))
        val f = tmp.newFile("fax.pdf")
        f.outputStream().asSink().use { FaxPdf.write(pages, it) }
        // small: the fax's own coding
        assertTrue("${f.length()} bytes", f.length() < 200_000)
        val back = images(f, tmp.newFolder("img"))
        assertEquals(2, back.size)
        for (i in pages.indices) {
            assertEquals(pages[i].rows, back[i].rows)
            assertArrayEquals("page ${i + 1}", pages[i].bits, back[i].bits)
        }
        // and drawn: pdftoppm at the fax's resolution, the page's size (8.47 in across: 1728 at 204 dpi)
        val ppm = ProcessBuilder("pdftoppm", "-r", "204", "-mono", "-f", "1", "-l", "1", f.path, File(tmp.root, "page").path)
            .redirectErrorStream(true).start()
        assertEquals(0, ppm.waitFor())
        val drawn = tmp.root.listFiles { x -> x.name.startsWith("page") }!!.single()
        val head = drawn.readBytes().copyOf(20).decodeToString()
        assertTrue(head, head.startsWith("P4\n1728 "))
    }
}
