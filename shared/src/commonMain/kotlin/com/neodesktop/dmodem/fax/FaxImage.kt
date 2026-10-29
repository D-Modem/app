// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.jobs.Latin1

/**
 * Grey images as fax pages (iOS draws its documents into grey pixels at
 * the fax's size; this makes them black and white): text and drawings at
 * a threshold, photographs dithered (Floyd–Steinberg), as the Android
 * app's FaxDocuments does its bitmaps.
 */
object FaxImage {
    /** 98 or 196 lines to the inch down (204 pels across, always) */
    fun lpi(fine: Boolean) = if (fine) 196 else 98

    /** the rows a page [width] by [height] (any units) takes at the fax's width */
    fun rows(width: Int, height: Int, fine: Boolean): Int =
        (height.toDouble() / width * FaxPage.WIDTH * lpi(fine) / 204).toInt().coerceIn(1, 4000)

    /**
     * [gray] (one octet a pixel, 0 black to 255 white, [width] to a row)
     * as a page [FaxPage.WIDTH] wide: a narrower image's right white, a
     * wider one's cut off
     */
    fun page(gray: ByteArray, width: Int, height: Int, fine: Boolean, dither: Boolean): FaxPage {
        require(gray.size >= width * height) { "${gray.size} octets for $width by $height" }
        val w = FaxPage.WIDTH
        val page = FaxPage(w, height, fine)
        /** the gray at [x], [y]: white right of the image */
        fun at(x: Int, y: Int): Int = if (x < width) gray[y * width + x].toInt() and 0xff else 255
        if (!dither) {
            for (y in 0 until height) for (x in 0 until w) if (at(x, y) < 128) page.set(x, y, true)
            return page
        }
        var cur = IntArray(w + 2)
        var next = IntArray(w + 2)
        for (y in 0 until height) {
            for (x in 0 until w) cur[x + 1] += at(x, y) * 16
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
            next.fill(0)
        }
        return page
    }

    /** [page] with its grey pixels as Latin-1 text (Swift's) */
    fun pageLatin1(gray: String, width: Int, height: Int, fine: Boolean, dither: Boolean): FaxPage =
        page(Latin1.bytes(gray), width, height, fine, dither)
}
