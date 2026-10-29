// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.terminal.Terminal

class DModemApp : Application() {

    companion object {
        const val CHANNEL_MODEM = "modem"
        const val CHANNEL_CALLS = "calls"
    }

    /** the notification channels made; the terminal, fax and dial-in set up; the log and the DTE's console started */
    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            CHANNEL_MODEM, getString(R.string.channel_modem), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_modem_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        // (a call ringing in: heads-up, the phone's ringtone)
        val calls = NotificationChannel(
            CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_calls_desc)
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(calls)

        Terminal.init(this)
        // fax calls: their settings and files, and calls that ring answered as faxes when they say so
        com.neodesktop.dmodem.fax.Fax.init(this)
        // dial-in: callers given the internet when the settings say so (after the fax's watch: its calls first)
        com.neodesktop.dmodem.ppp.DialIn.init(this)

        // the log's first lines: which build, when; the USB modems plugged in, and as they're plugged in and out
        ModemState.log.add("app: ${BuildInfo.described}, started ${com.neodesktop.dmodem.modem.LogFormat.now()}")
        com.neodesktop.dmodem.modem.UsbModems.watch(this) { ModemState.log.add("app: $it") }

        // what the modem sends its DTE, as text, for the status screen's console
        ModemState.dte.addListener { data, length ->
            ModemState.console.append(String(data, 0, length, Charsets.ISO_8859_1))
        }
    }
}
