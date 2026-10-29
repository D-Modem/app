// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.fax

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.IOException

/**
 * Documents as fax pages, and fax pages as a document: a PDF's pages,
 * an image, plain text, each scaled to the fax's width (1728 pels, 204
 * to the inch; 196 or 98 lines to the inch down) and made black and
 * white (text and PDFs at a threshold, photographs dithered). (Received
 * pages become a PDF in [FaxPdf].)
 */
object FaxDocuments {

    /** the pages of the document at [uri] (a PDF, an image, text) */
    fun pages(context: Context, uri: Uri, fine: Boolean): List<FaxPage> {
        val r = context.contentResolver
        val type = r.getType(uri) ?: ""
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            type == "application/pdf" || name.endsWith(".pdf") -> pdf(context, uri, fine)
            type.startsWith("image/") || Regex("\\.(png|jpe?g|gif|bmp|webp)$").containsMatchIn(name) -> {
                val bmp = r.openInputStream(uri)?.use { decodeScaled(it.readBytes()) } ?: throw IOException("can't read the image")
                listOf(fromBitmap(bmp, fine, dither = true)).also { bmp.recycle() }
            }
            type.startsWith("text/") || name.endsWith(".txt") -> {
                val text = r.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("can't read the text")
                text(text, fine)
            }
            else -> throw IOException("not a PDF, an image or text: ${type.ifEmpty { name }}")
        }
    }

    /** each page of the PDF at [uri] rendered at the fax's width, black and white */
    private fun pdf(context: Context, uri: Uri, fine: Boolean): List<FaxPage> {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("can't open the PDF")
        return fd.use { pfd ->
            PdfRenderer(pfd).use { pdf ->
                (0 until pdf.pageCount).map { i ->
                    pdf.openPage(i).use { pg ->
                        // the page at the fax's width; its height in the fax's lines
                        val h = (pg.height.toDouble() / pg.width * FaxPage.WIDTH * lpi(fine) / 204).toInt().coerceIn(1, 4000)
                        val bmp = Bitmap.createBitmap(FaxPage.WIDTH, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        threshold(bmp, fine).also { bmp.recycle() }
                    }
                }
            }
        }
    }

    /** the fax's lines per inch: fine 196, else 98 */
    private fun lpi(fine: Boolean) = if (fine) 196 else 98

    /** an image decoded no bigger than it need be for a fax page */
    private fun decodeScaled(b: ByteArray): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= FaxPage.WIDTH) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
            ?: throw IOException("not an image")
    }

    /** [b] as a page: scaled to the width, black and white ([dither]: for photographs) */
    fun fromBitmap(b: Bitmap, fine: Boolean, dither: Boolean): FaxPage {
        val h = (b.height.toDouble() / b.width * FaxPage.WIDTH * lpi(fine) / 204).toInt().coerceIn(1, 4000)
        val s = Bitmap.createBitmap(FaxPage.WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(s)
        c.drawColor(Color.WHITE)
        c.drawBitmap(b, Rect(0, 0, b.width, b.height), Rect(0, 0, FaxPage.WIDTH, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return (if (dither) dither(s, fine) else threshold(s, fine)).also { s.recycle() }
    }

    /** [p]'s brightness, 0..255 */
    private fun luma(p: Int): Int = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    /** [b] as a page: each pixel black where it's opaque and dark */
    private fun threshold(b: Bitmap, fine: Boolean): FaxPage {
        val page = FaxPage(b.width, b.height, fine)
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until b.width) if (Color.alpha(row[x]) > 127 && luma(row[x]) < 128) page.set(x, y, true)
        }
        return page
    }

    /** Floyd–Steinberg: the greys as dots */
    private fun dither(b: Bitmap, fine: Boolean): FaxPage {
        val w = b.width
        val page = FaxPage(w, b.height, fine)
        val row = IntArray(w)
        var cur = IntArray(w + 2)
        var next = IntArray(w + 2)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) cur[x + 1] += luma(row[x]) * 16
            for (x in 0 until w) {
                val v = cur[x + 1]
                val black = v < 128 * 16
                if (black) page.set(x, y, true)
                val e = v - (if (black) 0 else 255 * 16)
                cur[x + 2] += e * 7 / 16
                next[x] += e * 3 / 16
                next[x + 1] += e * 5 / 16
                next[x + 2] += e / 16
            }
            val t = cur; cur = next; next = t
            java.util.Arrays.fill(next, 0)
        }
        return page
    }

    /** plain text on A4 pages: monospaced, 66 lines and 80 columns to a page */
    fun text(text: String, fine: Boolean): List<FaxPage> {
        val lines = text.replace("\r\n", "\n").replace('\t', ' ').split('\n').flatMap { l ->
            if (l.isEmpty()) listOf("") else l.chunked(80)
        }
        val rows = FaxPage.a4Rows(fine)
        val perPage = 66
        return lines.chunked(perPage).ifEmpty { listOf(emptyList()) }.map { chunk ->
            // (drawn at fine resolution's proportions, then as the page's)
            val bmp = Bitmap.createBitmap(FaxPage.WIDTH, FaxPage.a4Rows(true), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                color = Color.BLACK
                textSize = 1500f / 80 / 0.6f
            }
            val top = 120f
            val step = (FaxPage.a4Rows(true) - 2 * top) / perPage
            for ((i, l) in chunk.withIndex()) c.drawText(l, 110f, top + (i + 1) * step, p)
            val page = if (fine) threshold(bmp, true) else {
                val half = Bitmap.createScaledBitmap(bmp, FaxPage.WIDTH, rows, true)
                threshold(half, false).also { half.recycle() }
            }
            bmp.recycle()
            page
        }
    }

    /** a page as a bitmap, black on white */
    fun toBitmap(p: FaxPage): Bitmap {
        val bmp = Bitmap.createBitmap(p.width, p.rows.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val row = IntArray(p.width)
        for (y in 0 until p.rows) {
            for (x in 0 until p.width) row[x] = if (p.black(x, y)) Color.BLACK else Color.WHITE
            bmp.setPixels(row, 0, p.width, 0, y, p.width, 1)
        }
        return bmp
    }
}
