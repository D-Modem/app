// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

actual class Inet4(internal val bytes: ByteArray) {
    override fun equals(other: Any?) = other is Inet4 && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "/" + dotted
}

actual fun inet4(octets: ByteArray): Inet4 {
    require(octets.size == 4) { "an IPv4 address is 4 octets" }
    return Inet4(octets.copyOf())
}

actual val Inet4.octets: ByteArray get() = bytes.copyOf()

actual val Inet4.dotted: String get() = bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
