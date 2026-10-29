// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.io

actual class Inet4(internal val bytes: ByteArray) {
    /** the same four octets */
    override fun equals(other: Any?) = other is Inet4 && bytes.contentEquals(other.bytes)
    /** the octets' hash */
    override fun hashCode() = bytes.contentHashCode()
    /** "/" and the dotted address, as the JVM's has it */
    override fun toString() = "/" + dotted
}

/** the address of these four octets (a copy of them) */
actual fun inet4(octets: ByteArray): Inet4 {
    require(octets.size == 4) { "an IPv4 address is 4 octets" }
    return Inet4(octets.copyOf())
}

actual val Inet4.octets: ByteArray get() = bytes.copyOf()

actual val Inet4.dotted: String get() = bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
