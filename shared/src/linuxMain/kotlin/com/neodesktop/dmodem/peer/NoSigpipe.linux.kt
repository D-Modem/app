// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import platform.posix.MSG_NOSIGNAL

/** (Linux: send()'s flag, no SIGPIPE for a connection gone) */
internal actual fun noSigpipe(fd: Int) {}

internal actual val SEND_FLAGS: Int = MSG_NOSIGNAL
