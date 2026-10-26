// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.neodesktop.dmodem.NativeModem
import java.io.IOException
import java.util.UUID

/**
 * A modem of its own on Bluetooth (the line LINE_BT_MODEM): a Sony BTA-NW1, say, or any device with
 * Bluetooth's Dial-Up Networking profile. It takes the app's AT commands and data in place of D-Modem's
 * own modem: the terminal, the internet and fax talk to it as they would to D-Modem's.
 */
@SuppressLint("MissingPermission") // (the service checks the permission first)
object BluetoothModem {
    /** Dial-Up Networking's service (DUN), and the serial port's (SPP) that some offer as well or instead */
    val DUN: UUID = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB")
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Connected to the paired device at [address]: its Dial-Up Networking service, then without
     * encryption (an older device's pairing may not give one), then its serial port; each try said to [log].
     */
    fun connect(context: Context, address: String, log: (String) -> Unit): BluetoothSocket {
        val a = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("this phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        // (a search going on slows a connection down)
        runCatching { a.cancelDiscovery() }
        val d = a.getRemoteDevice(address)
        val tries = listOf<Pair<String, () -> BluetoothSocket>>(
            "Dial-Up Networking" to { d.createRfcommSocketToServiceRecord(DUN) },
            "Dial-Up Networking, unencrypted" to { d.createInsecureRfcommSocketToServiceRecord(DUN) },
            "its serial port (SPP)" to { d.createRfcommSocketToServiceRecord(SPP) },
        )
        var last: Exception? = null
        for ((what, make) in tries) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                log("connected to ${d.name ?: address} ($what)")
                return s
            } catch (e: IOException) {
                runCatching { s?.close() }
                log("$what: ${e.message}")
                last = e
            } catch (e: SecurityException) {
                throw IOException("Bluetooth isn't allowed: allow it in the settings")
            }
        }
        throw IOException("it doesn't answer (on, paired, in reach?): ${last?.message}")
    }
}

/**
 * The status of a modem that says only its result codes (a Bluetooth modem's): online from its CONNECT
 * (and its speed), idle again from NO CARRIER, BUSY, NO DIALTONE or NO ANSWER. Fed what the modem sends.
 */
class ResultCodeStatus(private val onStatus: (NativeModem.Status) -> Unit) {
    private val line = StringBuilder()
    private var status = ModemState.IDLE

    fun feed(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            val c = data[i].toInt() and 0xff
            if (c == '\r'.code || c == '\n'.code) {
                result(line.toString().trim())
                line.setLength(0)
            } else if (line.length < MAX) {
                line.append(c.toChar())
            }
        }
    }

    private fun result(r: String) {
        val next = when {
            r.startsWith("CONNECT") -> {
                // (CONNECT 26400, CONNECT 26400/ARQ/V90: its speed, if it says)
                val bps = Regex("""^CONNECT\s+(\d+)""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                NativeModem.Status(NativeModem.Link.ONLINE, true, bps, bps)
            }
            r in ENDS -> ModemState.IDLE
            else -> return
        }
        if (next != status) {
            status = next
            onStatus(next)
        }
    }

    private companion object {
        /** longer lines aren't result codes (data online) */
        const val MAX = 40
        val ENDS = setOf("NO CARRIER", "BUSY", "NO DIALTONE", "NO DIAL TONE", "NO ANSWER")
    }
}
