// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm

actual open class IOException actual constructor(message: String?) : Exception(message)

@OptIn(ExperimentalForeignApi::class)
actual fun localOffsetSeconds(epochSec: Long): Int = memScoped {
    val t = alloc<time_tVar>()
    t.value = epochSec.convert()
    val r = alloc<tm>()
    if (localtime_r(t.ptr, r.ptr) == null) 0 else r.tm_gmtoff.toInt()
}
