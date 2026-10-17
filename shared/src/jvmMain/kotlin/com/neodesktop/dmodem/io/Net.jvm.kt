// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import java.net.InetAddress

actual typealias Inet4 = java.net.Inet4Address

actual fun inet4(octets: ByteArray): Inet4 = InetAddress.getByAddress(octets) as java.net.Inet4Address

actual val Inet4.octets: ByteArray get() = address

actual val Inet4.dotted: String get() = hostAddress ?: ""
