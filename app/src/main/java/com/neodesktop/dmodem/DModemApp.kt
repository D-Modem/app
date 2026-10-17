// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.terminal.Terminal

class DModemApp : Application() {

    companion object {
        const val CHANNEL_MODEM = "modem"
    }

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            CHANNEL_MODEM, getString(R.string.channel_modem), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_modem_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        Terminal.init(this)
        // fax calls: their settings and files, and calls that ring answered as faxes when they say so
        com.neodesktop.dmodem.fax.Fax.init(this)

        // what the modem sends its DTE, as text, for the status screen's console
        ModemState.dte.addListener { data, length ->
            ModemState.console.append(String(data, 0, length, Charsets.ISO_8859_1))
        }
    }
}
