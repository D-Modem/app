// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A VT220's user-defined keys (DECUDK): what the host loads into F6 to
 * F20, sent when the key is pressed with Shift. The keys go by DEC's
 * numbers (17 F6 ... 34 F20, as their CSI ~ sequences have them); the
 * host can lock them against more loading until the terminal is reset.
 */
class UserKeys {
    private val keys = HashMap<Int, ByteArray>()
    /** loading them refused (DECUDK's Pl 0, or the set-up's lock) */
    var locked = false
        private set

    /**
     * a DECUDK's data: [clear] 0 (or none) all keys cleared first, 1 only
     * those given replaced; [lock] 0 (or none) locked after, 1 left
     * unlocked; [data] "key/hex-string" pairs between semicolons
     */
    fun load(clear: Int, lock: Int, data: String) {
        if (locked) return
        if (clear == 0) keys.clear()
        for (def in data.split(';')) {
            val slash = def.indexOf('/')
            if (slash < 0) continue
            val key = def.substring(0, slash).toIntOrNull() ?: continue
            if (key !in KEYS) continue
            val hex = def.substring(slash + 1)
            if (hex.isEmpty()) {
                keys.remove(key)
                continue
            }
            val out = ByteArray(hex.length / 2)
            var ok = hex.length % 2 == 0
            for (i in out.indices) {
                val v = hex.substring(2 * i, 2 * i + 2).toIntOrNull(16)
                if (v == null) { ok = false; break }
                out[i] = v.toByte()
            }
            if (ok && out.size <= 256) keys[key] = out
        }
        locked = lock == 0
    }

    /** DEC's key [number]'s string, or null when none is loaded */
    fun get(number: Int): ByteArray? = keys[number]

    /** [k]'s string when it is one of the keys and has one */
    fun forKey(k: Key): ByteArray? = NUMBERS[k]?.let { keys[it] }

    /** all cleared and unlocked (RIS) */
    fun reset() {
        keys.clear()
        locked = false
    }

    companion object {
        /** the keys a host can load: F6-F10, F11-F14, Help and Do (F15, F16), F17-F20 */
        val KEYS = setOf(17, 18, 19, 20, 21, 23, 24, 25, 26, 28, 29, 31, 32, 33, 34)

        /** the function keys' numbers */
        private val NUMBERS = mapOf(
            Key.F6 to 17, Key.F7 to 18, Key.F8 to 19, Key.F9 to 20, Key.F10 to 21,
            Key.F11 to 23, Key.F12 to 24, Key.F13 to 25, Key.F14 to 26, Key.F15 to 28, Key.F16 to 29,
            Key.F17 to 31, Key.F18 to 32, Key.F19 to 33, Key.F20 to 34,
        )
    }
}
