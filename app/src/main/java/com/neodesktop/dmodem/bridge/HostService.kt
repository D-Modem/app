// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.neodesktop.dmodem.DModemApp
import com.neodesktop.dmodem.R
import com.neodesktop.dmodem.settings.BridgeSettingsStore
import com.neodesktop.dmodem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The bridges in internet mode (the phone as a dial-up ISP for computers
 * on its serial ports), in the foreground, the modem and its SIP account
 * not needed: on while the settings say so and a way for a computer to
 * come is on.
 */
class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false

    /** not bound: started only */
    override fun onBind(intent: Intent?): IBinder? = null

    /** in the foreground; the bridges kept as the settings are (stopped with the service when internet mode or every bridge is off), the notification saying who's online */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (watching) return START_STICKY
        watching = true
        scope.launch {
            combine(BridgeSettingsStore(this@HostService).settings, Bridges.serial.client) { s, c -> s to c }.collect { (s, c) ->
                if (!s.internet || !s.anyOn) {
                    Bridges.stopAll()
                    stopSelf()
                    return@collect
                }
                Bridges.apply(this@HostService, s)
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(c?.let { "Online: ${it.name} (${it.via})" } ?: "Waiting for a computer to dial"))
            }
        }
        return START_STICKY
    }

    /** the watch cancelled, the bridges stopped */
    override fun onDestroy() {
        scope.cancel()
        Bridges.stopAll()
        super.onDestroy()
    }

    /** the ongoing notification, saying [text], opening the app */
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, DModemApp.CHANNEL_MODEM)
            .setSmallIcon(R.drawable.ic_modem)
            .setContentTitle("Internet for serial computers")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 3

        /** on (or off again) as the settings are */
        fun update(context: Context) {
            context.startForegroundService(Intent(context, HostService::class.java))
        }
    }
}
