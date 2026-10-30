// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neodesktop.dmodem.modem.LogFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The modem's settings: the SIP account and how the modem runs. */
data class ModemSettings(
    /** the SIP account: user, password, registrar or proxy (host[:port]) */
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** the local SIP port */
    val sipPort: Int = 5060,
    /** register, and answer calls (RING on the DTE, ATA or ATS0=n) */
    val listen: Boolean = false,
    /** fax calls may go over to T.38 */
    val t38: Boolean = true,
    /** the modem's country by name (empty: USA) */
    val country: String = "",
    /** AT commands sent to the modem each time it starts (empty: none) */
    val initCommands: String = "ATX3",
    /** the modem's log level (0 errors .. 5), SIP's (0 none .. 4 the SIP messages) */
    val logLevel: Int = 0,
    val sipLogLevel: Int = 1,
    /** the log's time stamp before each line, a date pattern (SimpleDateFormat's; empty: none) */
    val logTimestamp: String = LogFormat.DEFAULT,
    /** the modem's line: a SIP account, or another phone with the app over Wi-Fi or Bluetooth */
    val line: String = LINE_SIP,
    /** the other phone to call: host[:port] on Wi-Fi, a paired phone's address on Bluetooth */
    val peerAddress: String = "",
    /** the paired phone's name, to show */
    val peerName: String = "",
    /** the TCP port phones call each other on */
    val peerPort: Int = 5070,
    /** the PIN between the two phones (empty: none): callers must know this phone's, ours answers the other's */
    val peerPin: String = "",
    /** the modem's speaker on the phone's: the dialling and the handshake heard (as ATM and ATL have it) */
    val speaker: Boolean = true,
    /** the country's call progress tones (dial, ringing, busy, congestion) where the line has none of its own */
    val callTones: Boolean = true,
    /**
     * calls answered as fax or data by what they turn out to be (D-Modem-RE's adaptive answer, AT+FAA=1):
     * a fax machine's to the fax receiver, a modem's to the terminal
     */
    val adaptiveAnswer: Boolean = true,
    /**
     * the modulation on mobile calls (+MS): VCVM (V.cvm, made for speech codecs: about 1200 bit/s
     * through AMR 12.2, to another D-Modem; V.22 or V.21 to other modems), V21 (300 bit/s), V22, V22B
     */
    val cellSpeed: String = CELL_VCVM,
    /** the Bluetooth modem's (LINE_BT_MODEM): the paired device's address, its name to show */
    val btModemAddress: String = "",
    val btModemName: String = "",
    /** each call on a USB modem's line recorded (LineRecordings), for a bug report */
    val recordLine: Boolean = false,
    /** a USB modem's line: the modem's sound to it louder or softer, dB (-20 to 20; D-Modem-RE's DMODEM_USB_TX_GAIN) */
    val usbTxGain: Int = 0,
    /** a USB modem's line: its transmit level set for each call from the remote's answer tone (DMODEM_USB_TX_GAIN=auto), not [usbTxGain] */
    val usbTxAuto: Boolean = true,
) {
    /** D-Modem-RE's DMODEM_USB_TX_GAIN for this line: "auto", the dB, or null (none: 0 dB, or not a USB line) */
    val usbTxGainEnv: String?
        get() = when {
            !usb -> null
            usbTxAuto -> "auto"
            usbTxGain != 0 -> usbTxGain.coerceIn(-20, 20).toString()
            else -> null
        }

    /** the line is another phone (or this phone's mobile calls, or none), not SIP: the app carries its samples */
    val peer: Boolean get() = line != LINE_SIP && line != LINE_USB && line != LINE_BT_MODEM

    /** the line is a SIP account's */
    val sip: Boolean get() = line == LINE_SIP

    /** the line is a phone line, through a USB modem (UsbModems): the modem dials it itself */
    val usb: Boolean get() = line == LINE_USB

    /** a modem of its own on Bluetooth (BluetoothModem) in place of D-Modem's: it has the line */
    val btModem: Boolean get() = line == LINE_BT_MODEM

    /** no line at all: the modem on its own (AT commands, the terminal, the bridges); a dial gets NO DIALTONE */
    val noLine: Boolean get() = line == LINE_NONE

    /** Enough to start: an account (or the line another phone's). */
    val complete: Boolean get() = !sip || (user.isNotBlank() && domain.isNotBlank())

    /** the commands sent as the modem starts: the init commands, on mobile calls the modulation first */
    fun startCommands(): String {
        val init = initCommands.trim()
        if (line != LINE_CELL) return init
        // (V.cvm's start-up, probing the codec, takes 15 to 21 s after V.8: S7 has room for it)
        val ms = "AT+MS=$cellSpeed" + if (cellSpeed == CELL_VCVM) ";S7=90" else ""
        return when {
            init.isEmpty() -> ms
            init.startsWith("AT", ignoreCase = true) -> "$ms;" + init.substring(2)
            else -> init.lines().joinToString("\r") + "\r" + ms
        }
    }

    companion object {
        const val LINE_SIP = "sip"
        const val LINE_WIFI = "wifi"
        const val LINE_BLUETOOTH = "bluetooth"
        const val LINE_CELL = "cell"
        const val LINE_NONE = "none"
        const val LINE_USB = "usb"
        const val LINE_BT_MODEM = "bt_modem"
        /** the mobile-call line's V.cvm (D-Modem-RE's +MS=VCVM) */
        const val CELL_VCVM = "VCVM"

        /**
         * An account as `user:password@host[:port]` (dmodemd's SIP_LOGIN): null if it isn't one.
         * The password may have an @: the domain is after the last.
         */
        fun parseLogin(login: String): Triple<String, String, String>? {
            val at = login.lastIndexOf('@')
            val colon = login.indexOf(':')
            if (at < 0 || colon < 0 || colon > at) return null
            val user = login.substring(0, colon)
            val domain = login.substring(at + 1)
            if (user.isEmpty() || domain.isEmpty()) return null
            return Triple(user, login.substring(colon + 1, at), domain)
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("modem")

/** The settings in the app's DataStore (its private storage). */
class SettingsStore(private val context: Context) {

    private object Keys {
        val user = stringPreferencesKey("user")
        val password = stringPreferencesKey("password")
        val domain = stringPreferencesKey("domain")
        val sipPort = intPreferencesKey("sip_port")
        val listen = booleanPreferencesKey("listen")
        val t38 = booleanPreferencesKey("t38")
        val country = stringPreferencesKey("country")
        val initCommands = stringPreferencesKey("init_commands")
        val logLevel = intPreferencesKey("log_level")
        val sipLogLevel = intPreferencesKey("sip_log_level")
        val logTimestamp = stringPreferencesKey("log_timestamp")
        val line = stringPreferencesKey("line")
        val peerAddress = stringPreferencesKey("peer_address")
        val peerName = stringPreferencesKey("peer_name")
        val peerPort = intPreferencesKey("peer_port")
        val peerPin = stringPreferencesKey("peer_pin")
        val speaker = booleanPreferencesKey("speaker")
        val callTones = booleanPreferencesKey("callTones")
        val adaptiveAnswer = booleanPreferencesKey("adaptiveAnswer")
        val cellSpeed = stringPreferencesKey("cell_speed")
        val btModemAddress = stringPreferencesKey("bt_modem_address")
        val btModemName = stringPreferencesKey("bt_modem_name")
        val recordLine = booleanPreferencesKey("record_line")
        val usbTxGain = intPreferencesKey("usb_tx_gain")
        val usbTxAuto = booleanPreferencesKey("usb_tx_auto")
    }

    val settings: Flow<ModemSettings> = context.dataStore.data.map { p ->
        val d = ModemSettings()
        ModemSettings(
            user = p[Keys.user] ?: d.user,
            password = p[Keys.password] ?: d.password,
            domain = p[Keys.domain] ?: d.domain,
            sipPort = p[Keys.sipPort] ?: d.sipPort,
            listen = p[Keys.listen] ?: d.listen,
            t38 = p[Keys.t38] ?: d.t38,
            country = p[Keys.country] ?: d.country,
            initCommands = p[Keys.initCommands] ?: d.initCommands,
            logLevel = p[Keys.logLevel] ?: d.logLevel,
            sipLogLevel = p[Keys.sipLogLevel] ?: d.sipLogLevel,
            logTimestamp = p[Keys.logTimestamp] ?: d.logTimestamp,
            line = p[Keys.line] ?: d.line,
            peerAddress = p[Keys.peerAddress] ?: d.peerAddress,
            peerName = p[Keys.peerName] ?: d.peerName,
            peerPort = p[Keys.peerPort] ?: d.peerPort,
            peerPin = p[Keys.peerPin] ?: d.peerPin,
            speaker = p[Keys.speaker] ?: d.speaker,
            callTones = p[Keys.callTones] ?: d.callTones,
            adaptiveAnswer = p[Keys.adaptiveAnswer] ?: d.adaptiveAnswer,
            // V_CVM: the name before VCVM, kept by older versions
            cellSpeed = (p[Keys.cellSpeed] ?: d.cellSpeed).let { if (it == "V_CVM") CELL_VCVM else it },
            btModemAddress = p[Keys.btModemAddress] ?: d.btModemAddress,
            btModemName = p[Keys.btModemName] ?: d.btModemName,
            recordLine = p[Keys.recordLine] ?: d.recordLine,
            usbTxGain = p[Keys.usbTxGain] ?: d.usbTxGain,
            usbTxAuto = p[Keys.usbTxAuto] ?: d.usbTxAuto,
        )
    }

    /** what's stored now */
    suspend fun current(): ModemSettings = settings.first()

    /** [s] stored (addresses and names trimmed, the USB gain kept in -20..20) */
    suspend fun save(s: ModemSettings) {
        context.dataStore.edit { p ->
            p[Keys.user] = s.user.trim()
            p[Keys.password] = s.password
            p[Keys.domain] = s.domain.trim()
            p[Keys.sipPort] = s.sipPort
            p[Keys.listen] = s.listen
            p[Keys.t38] = s.t38
            p[Keys.country] = s.country.trim()
            p[Keys.initCommands] = s.initCommands.trim()
            p[Keys.logLevel] = s.logLevel
            p[Keys.sipLogLevel] = s.sipLogLevel
            p[Keys.logTimestamp] = s.logTimestamp
            p[Keys.line] = s.line
            p[Keys.peerAddress] = s.peerAddress.trim()
            p[Keys.peerName] = s.peerName
            p[Keys.peerPort] = s.peerPort
            p[Keys.peerPin] = s.peerPin.trim()
            p[Keys.speaker] = s.speaker
            p[Keys.callTones] = s.callTones
            p[Keys.adaptiveAnswer] = s.adaptiveAnswer
            p[Keys.cellSpeed] = s.cellSpeed
            p[Keys.btModemAddress] = s.btModemAddress
            p[Keys.btModemName] = s.btModemName
            p[Keys.recordLine] = s.recordLine
            p[Keys.usbTxGain] = s.usbTxGain.coerceIn(-20, 20)
            p[Keys.usbTxAuto] = s.usbTxAuto
        }
    }
}
