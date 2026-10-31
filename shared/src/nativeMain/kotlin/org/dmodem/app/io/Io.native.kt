// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm

actual open class IOException actual constructor(message: String?) : Exception(message)

/** the local time zone's offset from UTC at [epochSec], in seconds (localtime_r's; 0 if it fails) */
@OptIn(ExperimentalForeignApi::class)
actual fun localOffsetSeconds(epochSec: Long): Int = memScoped {
    val t = alloc<time_tVar>()
    t.value = epochSec.convert()
    val r = alloc<tm>()
    if (localtime_r(t.ptr, r.ptr) == null) 0 else r.tm_gmtoff.toInt()
}

/** (/dev/urandom: Linux's and iOS's) */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val f = fopen("/dev/urandom", "rb") ?: throw IOException("no /dev/urandom")
    try {
        val got = out.usePinned { fread(it.addressOf(0), 1u, n.convert(), f) }
        if (got.toInt() != n) throw IOException("/dev/urandom: short read")
    } finally {
        fclose(f)
    }
    return out
}
