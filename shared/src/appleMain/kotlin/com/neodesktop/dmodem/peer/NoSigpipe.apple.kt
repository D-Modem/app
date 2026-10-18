// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.setsockopt

/** (Apple's: the socket's own option, no SIGPIPE for a connection gone) */
@OptIn(ExperimentalForeignApi::class)
internal actual fun noSigpipe(fd: Int) {
    memScoped {
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
    }
}

internal actual val SEND_FLAGS: Int = 0
