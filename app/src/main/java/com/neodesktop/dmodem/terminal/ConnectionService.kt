// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import android.app.Notification
import android.app.NotificationManager
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
import com.neodesktop.dmodem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * In the foreground while SSH or Telnet sessions are open (so that they
 * outlive the activity), with a notification of them; it ends itself
 * when the last one has closed.
 */
class ConnectionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val ACTION_CLOSE_ALL = "com.neodesktop.dmodem.CLOSE_ALL"

        /** started if network sessions are open, stopped if none is */
        fun update(context: Context) {
            val app = context.applicationContext
            if (Terminal.openNetworkSessions() > 0) {
                app.startForegroundService(Intent(app, ConnectionService::class.java))
            } else {
                app.stopService(Intent(app, ConnectionService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        scope.launch {
            // every session's link state: the notification follows, and the end
            Terminal.sessions.flatMapLatest { list ->
                val states = list.filter { it.kind != TerminalSession.Kind.MODEM }.map { it.link.state }
                if (states.isEmpty()) flowOf(emptyList()) else combine(states) { it.toList() }
            }.collect {
                if (Terminal.openNetworkSessions() == 0) {
                    ServiceCompat.stopForeground(this@ConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            Terminal.sessions.value.filter { it.kind != TerminalSession.Kind.MODEM }.forEach { Terminal.close(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = Terminal.sessions.value.filter {
            it.kind != TerminalSession.Kind.MODEM && it.link.state.value !is TerminalLink.State.Closed
        }
        val text = when (open.size) {
            0 -> "No connections"
            1 -> open[0].label
            else -> "${open.size} connections: " + open.joinToString(", ") { it.label }
        }
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeAll = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_CLOSE_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DModemApp.CHANNEL_MODEM)
            .setSmallIcon(R.drawable.ic_modem)
            .setContentTitle("Connected")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(show)
            .addAction(0, "Disconnect all", closeAll)
            .build()
    }
}
