// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.neodesktop.dmodem.DModemApp
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.R
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import com.neodesktop.dmodem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Inet6Address
import java.util.concurrent.Executors

/**
 * The modem while it is on: the native modem (one per process) with its
 * DTE on [ModemState.dte], in the foreground with a notification, the
 * CPU and Wi-Fi kept awake for the call's real-time audio.
 *
 * The SIP user agent binds to the addresses the phone has when it
 * starts: when the default network changes the modem is started again,
 * once it is on hook (a call in progress is lost with the old network
 * anyway).
 */
class ModemService : Service() {

    companion object {
        private const val TAG = "ModemService"
        private const val ACTION_START = "com.neodesktop.dmodem.START"
        private const val ACTION_STOP = "com.neodesktop.dmodem.STOP"
        private const val NOTIFICATION_ID = 1
        private const val DATA_FILE = "dsp_data"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ModemService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ModemService::class.java).setAction(ACTION_STOP))
        }
    }

    /** start, stop and restart, one at a time and off the main thread (they block) */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "modem-control") }
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())

    private lateinit var store: SettingsStore
    private var settings = ModemSettings()

    /** we are stopping the modem (its exit is ours, not an error) */
    @Volatile
    private var stopping = false

    /** the network changed during a call: start again once on hook */
    @Volatile
    private var restartPending = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** the default network and its addresses the modem started on */
    private var netKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        scope.launch {
            combine(ModemState.run, ModemState.status) { r, s -> r to s }.collect { (r, s) ->
                if (r != ModemState.Run.STOPPED) notify(notification(r, s))
                if (!s.offhook && restartPending && r == ModemState.Run.RUNNING) {
                    restartPending = false
                    log("the network changed: the modem starts again")
                    restart()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopModem() }
                return START_NOT_STICKY
            }
            else -> {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    notification(ModemState.Run.STARTING, ModemState.IDLE),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
                )
                scope.launch { startModem() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking(scope.coroutineContext) { shutdown() }
        scope.cancel()
        worker.shutdown()
        super.onDestroy()
    }

    /** the modem started with the settings, if it isn't running; the service ends if it can't */
    private suspend fun startModem() {
        if (ModemState.run.value == ModemState.Run.RUNNING) return
        settings = store.current()
        if (!settings.complete) {
            fail("no SIP account: set one in the settings")
            return
        }
        ModemState.setError(null)
        ModemState.setRun(ModemState.Run.STARTING)
        if (!startNative()) {
            fail(ModemState.error.value ?: "the modem didn't start")
            return
        }
        acquireLocks()
        watchNetwork()
        ModemState.setRun(ModemState.Run.RUNNING)
    }

    /** the native modem on the default network now, its DTE on the hub, the init commands sent */
    private fun startNative(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        if (lp == null) {
            ModemState.setError("no network")
            return false
        }
        netKey = keyOf(net, lp)
        NativeModem.listener = nativeListener
        stopping = false
        val config = NativeModem.Config(
            user = settings.user,
            password = settings.password,
            domain = settings.domain,
            sipPort = settings.sipPort,
            listen = settings.listen,
            t38 = settings.t38,
            dns = lp.dnsServers.filter { !(it is Inet6Address && it.isLinkLocalAddress) }
                .mapNotNull { it.hostAddress },
            country = settings.country,
            dataFile = File(filesDir, DATA_FILE).path,
            logLevel = settings.logLevel,
            sipLogLevel = settings.sipLogLevel,
        )
        val pfd = NativeModem.start(config)
        if (pfd == null) {
            ModemState.setError("the modem didn't start (is it running already?)")
            return false
        }
        ModemState.dte.attach(pfd)
        ModemState.setStatus(ModemState.IDLE)
        if (settings.initCommands.isNotBlank()) {
            ModemState.dte.write(settings.initCommands.trim() + "\r")
        }
        return true
    }

    /** the native modem stopped: the call hung up, SIP gone, the DTE's socket closed */
    private fun stopNative() {
        stopping = true
        NativeModem.stop()
        ModemState.dte.detach()
        ModemState.setStatus(ModemState.IDLE)
    }

    /** stopped again and started on the network there is now (the service stays) */
    private fun restart() {
        stopNative()
        if (!startNative()) {
            fail(ModemState.error.value ?: "the modem didn't start again")
        }
    }

    private fun stopModem() {
        shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** everything let go: the modem, the network watch, the locks */
    private fun shutdown() {
        if (ModemState.run.value != ModemState.Run.STOPPED) {
            ModemState.setRun(ModemState.Run.STOPPING)
            stopNative()
        }
        unwatchNetwork()
        releaseLocks()
        restartPending = false
        ModemState.setRun(ModemState.Run.STOPPED)
    }

    private fun fail(why: String) {
        log("error: $why")
        ModemState.setError(why)
        stopModem()
    }

    private val nativeListener = object : NativeModem.Listener {
        override fun onLog(line: String) {
            ModemState.log.add(line)
        }

        override fun onStatus(status: NativeModem.Status) {
            ModemState.setStatus(status)
        }

        override fun onExit(result: Int) {
            // (on the modem's thread: stop() waits for it, so nothing that stops here)
            if (stopping) return
            scope.launch {
                if (!stopping) fail(if (result < 0) "the modem stopped on an error (see the log)" else "the modem stopped")
            }
        }
    }

    // the network

    private fun keyOf(net: Network, lp: LinkProperties): String =
        "$net " + lp.linkAddresses.joinToString(",") { it.address.hostAddress ?: "" }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val key = keyOf(network, lp)
                scope.launch { networkChanged(key) }
            }

            override fun onLost(network: Network) {
                scope.launch { log("the network ${network} is gone") }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
        }
    }

    /** the default network (or its addresses) changed: the modem started on it, once on hook */
    private fun networkChanged(key: String) {
        if (key == netKey || ModemState.run.value != ModemState.Run.RUNNING) return
        log("the default network changed: $key")
        if (ModemState.status.value.offhook) {
            restartPending = true
        } else {
            restart()
        }
    }

    // locks

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            // (no timeout: held while the user keeps the modem on, released at its stop)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmodem:modem")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "dmodem:rtp")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // the notification

    private fun notification(run: ModemState.Run, s: NativeModem.Status): Notification {
        val text = when {
            run == ModemState.Run.STARTING -> "Starting…"
            run == ModemState.Run.STOPPING -> "Stopping…"
            s.link == NativeModem.Link.ONLINE -> "Online: ${s.rxRate}/${s.txRate} bit/s"
            s.link == NativeModem.Link.CONNECTING -> "Connecting…"
            s.link == NativeModem.Link.HANGING_UP -> "Hanging up…"
            s.offhook -> "Off hook"
            else -> "Ready" + if (settings.listen) ", taking calls" else ""
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ModemService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DModemApp.CHANNEL_MODEM)
            .setSmallIcon(R.drawable.ic_modem)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun notify(n: Notification) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // (no POST_NOTIFICATIONS: the foreground service goes on without it)
            Log.w(TAG, "notify: $e")
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        ModemState.log.add("app: $line")
    }
}
