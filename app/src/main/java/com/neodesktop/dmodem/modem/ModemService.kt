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
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.settings.BridgeSettingsStore
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import com.neodesktop.dmodem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        private const val NO_LOCAL_NETWORK = "Android hasn't allowed D-Modem on the local network, so a SIP server " +
            "there can't be reached (\"Operation not permitted\"): allow it in Android's settings, " +
            "Apps > D-Modem > Permissions, then start the modem again"
        private const val ACTION_START = "com.neodesktop.dmodem.START"
        private const val ACTION_STOP = "com.neodesktop.dmodem.STOP"
        /** the ringing call put out of sight (its notification's Ignore) */
        const val ACTION_IGNORE = "com.neodesktop.dmodem.IGNORE_CALL"
        private const val CALL_NOTIFICATION_ID = 3
        private const val NOTIFICATION_ID = 1
        private const val DATA_FILE = "dsp_data"
        /** the speaker's sound socket, kept open for the process's life */
        @Volatile private var speakerSocket: android.os.ParcelFileDescriptor? = null

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
    /** the USB modem that's the line (LINE_USB), open while the modem runs */
    private var usbModem: UsbModems.Opened.Device? = null

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
        // the TCP and Bluetooth bridges, as their settings are (now and as they change), while the modem is on
        scope.launch {
            combine(BridgeSettingsStore(this@ModemService).settings, ModemState.run) { b, r -> b to r }.collect { (b, r) ->
                // (in internet mode they're the host service's, the modem not needed)
                if (b.internet) return@collect
                if (r == ModemState.Run.RUNNING) Bridges.apply(this@ModemService, b)
                else if (r == ModemState.Run.STOPPED) Bridges.stopAll()
            }
        }
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
            ACTION_IGNORE -> {
                ModemState.incoming.ignore()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                scope.launch { stopModem() }
                return START_NOT_STICKY
            }
            else -> {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    notification(ModemState.Run.STARTING, ModemState.IDLE),
                    if (Build.VERSION.SDK_INT >= 34) serviceType() else 0,
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

    /** the foreground service's type: a mobile call's sound (the cellular line) is a recording's */
    @androidx.annotation.RequiresApi(34)
    private fun serviceType(): Int {
        val cell = runBlocking { store.current() }.line == ModemSettings.LINE_CELL
        val mic = cell && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }

    /** the modem started with the settings, if it isn't running; the service ends if it can't */
    private suspend fun startModem() {
        if (ModemState.run.value == ModemState.Run.RUNNING) return
        settings = store.current()
        if (!settings.complete) {
            fail("no SIP account: set one in the settings")
            return
        }
        if (settings.line == ModemSettings.LINE_CELL) {
            com.neodesktop.dmodem.cell.AndroidCallAudio(this).unavailable()?.let {
                fail("the line is the phone's mobile calls, but $it")
                return
            }
            if (com.neodesktop.dmodem.cell.AndroidCellPhone.PERMISSIONS.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                fail("the line is the phone's mobile calls, which aren't allowed: allow them in the settings")
                return
            }
        }
        if (settings.line == ModemSettings.LINE_BLUETOOTH && !com.neodesktop.dmodem.bridge.BluetoothBridge.hasPermission(this)) {
            fail("the line is Bluetooth, which isn't allowed: allow it in the settings")
            return
        }
        ModemState.setError(null)
        // (not a failure: the SIP server may be on the internet, which needs no leave)
        if (settings.sip && !localNetworkAllowed()) log(NO_LOCAL_NETWORK)
        // a USB modem: Android's leave to use it (asked now if need be), the device opened while the modem runs
        if (settings.usb) {
            when (val o = UsbModems.open(this)) {
                is UsbModems.Opened.Failed -> {
                    fail(o.why)
                    return
                }
                is UsbModems.Opened.Device -> {
                    usbModem = o
                    log("line: the ${o.kind.described}")
                }
            }
        }
        ModemState.setRun(ModemState.Run.STARTING)
        if (!startNative()) {
            fail(ModemState.error.value ?: "the modem didn't start")
            return
        }
        acquireLocks()
        // (a line to another phone doesn't care which network: nothing to start again for)
        if (settings.sip) watchNetwork()
        ModemState.setRun(ModemState.Run.RUNNING)
    }

    /** the native modem on the default network now, its DTE on the hub, the init commands sent */
    private fun startNative(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        if (lp == null && settings.sip) {
            ModemState.setError("no network")
            return false
        }
        netKey = if (net != null && lp != null) keyOf(net, lp) else null
        NativeModem.listener = nativeListener
        stopping = false
        val config = NativeModem.Config(
            user = settings.user,
            password = settings.password,
            domain = settings.domain,
            sipPort = settings.sipPort,
            listen = settings.listen,
            t38 = settings.t38,
            dns = lp?.dnsServers.orEmpty().filter { !(it is Inet6Address && it.isLinkLocalAddress) }
                .mapNotNull { it.hostAddress },
            country = settings.country,
            dataFile = File(filesDir, DATA_FILE).path,
            logLevel = settings.logLevel,
            sipLogLevel = settings.sipLogLevel,
            peer = settings.peer,
            usbFd = usbModem?.connection?.fileDescriptor ?: -1,
            usbRate = usbModem?.kind?.rate ?: UsbModems.RATE,
            // (Bluetooth's way is slower: more room for the samples on it)
            peerLeadMs = when (settings.line) {
                ModemSettings.LINE_BLUETOOTH -> 200
                // (the call's sound is the phone's audio's: its clock, its buffers)
                ModemSettings.LINE_CELL -> 60
                else -> 100
            },
        )
        val pfd = NativeModem.start(config)
        if (pfd == null) {
            ModemState.setError("the modem didn't start (is it running already?)")
            return false
        }
        ModemState.dte.attach(pfd)
        ModemState.setStatus(ModemState.IDLE)
        ModemState.speaker.enabled = settings.speaker
        ModemState.tones.enabled = settings.callTones
        ModemState.tones.country = com.neodesktop.dmodem.tones.CountryTones.find(settings.country)
            ?: com.neodesktop.dmodem.tones.CountryTones.find("")!!
        watchProgress()
        ModemState.line = settings.line
        if (settings.usb) watchUsb()
        // (the speaker's sound: one socket for the process, read for good once started)
        NativeModem.speakerSound()?.let { s ->
            speakerSocket = s
            ModemState.speaker.start(java.io.FileInputStream(s.fileDescriptor))
        }
        if (settings.peer && !settings.noLine) com.neodesktop.dmodem.peer.Peers.start(this, settings, ::log)
        val start = settings.startCommands()
        if (start.isNotBlank()) ModemState.dte.write(start + "\r")
        // (each call answered as fax or data by what it turns out to be: FAX, then the fax receiver)
        ModemState.adaptiveAnswer = settings.adaptiveAnswer
        if (settings.adaptiveAnswer) ModemState.dte.write("AT+FAA=1\r")
        return true
    }

    private var progressJob: kotlinx.coroutines.Job? = null
    private val progressText = com.neodesktop.dmodem.terminal.TerminalLink.Listener { d, n ->
        val text = String(d, 0, n, Charsets.ISO_8859_1)
        ModemState.progress.modemText(text)
        ModemState.incoming.modemText(text)
    }

    /** the call progress tones' watch: what the modem says, its state, the other phone ringing */
    private fun watchProgress() {
        progressJob?.cancel()
        ModemState.dte.removeListener(progressText)
        ModemState.dte.addListener(progressText)
        progressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            launch {
                ModemState.status.collect { st ->
                    ModemState.progress.status(st.offhook, st.link == NativeModem.Link.CONNECTING || st.link == NativeModem.Link.ONLINE)
                    ModemState.incoming.hook(st.offhook)
                }
            }
            launch {
                com.neodesktop.dmodem.peer.Peers.state.collect { st ->
                    ModemState.progress.ringingThere(st is com.neodesktop.dmodem.peer.PeerLine.State.Calling)
                    ModemState.incoming.caller((st as? com.neodesktop.dmodem.peer.PeerLine.State.Ringing)?.from)
                }
            }
            // (the RINGs stopped: the caller gave up)
            launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    ModemState.incoming.tick()
                }
            }
            // a call ringing in: the phone's incoming call notification while it rings
            launch {
                ModemState.incoming.state
                    .map { c -> c?.takeIf { !it.ignored }?.let { it.since to it.from } }
                    .distinctUntilChanged()
                    .collect { callNotification(ModemState.incoming.state.value?.takeIf { !it.ignored }) }
            }
        }
    }

    /** a USB modem as the line: Android's word when it goes from the bus, or comes back (a reset shows as both) */
    private var usbWatch: android.content.BroadcastReceiver? = null

    private fun watchUsb() {
        if (usbWatch != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE) ?: return
                val kind = UsbModems.kindOf(d) ?: return
                val gone = i.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
                log("USB: the ${kind.label} " + if (gone) "has gone from the bus (unplugged, or it reset)" else "is on the bus again")
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, r, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        usbWatch = r
    }

    private fun unwatchUsb() {
        usbWatch?.let { runCatching { unregisterReceiver(it) } }
        usbWatch = null
    }

    /** the native modem stopped: the call hung up, SIP gone, the DTE's socket closed */
    private fun stopNative() {
        stopping = true
        progressJob?.cancel()
        progressJob = null
        ModemState.dte.removeListener(progressText)
        ModemState.tones.stop()
        ModemState.incoming.end()
        callNotification(null)
        ModemState.speaker.setLevel(0)
        com.neodesktop.dmodem.peer.Peers.stop()
        NativeModem.stop()
        unwatchUsb()
        // (the device let go once the modem has)
        usbModem?.connection?.close()
        usbModem = null
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
        if (!runBlocking { BridgeSettingsStore(this@ModemService).current() }.internet) Bridges.stopAll()
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
            // (what Android says when the LAN isn't the app's to reach)
            if (line.endsWith("Operation not permitted") && !localNetworkAllowed()) log(NO_LOCAL_NETWORK)
        }

        override fun onStatus(status: NativeModem.Status) {
            ModemState.setStatus(status)
        }

        override fun onSpeaker(level: Int) {
            ModemState.speaker.setLevel(level)
        }

        override fun onLine(caller: Boolean, dial: String, line: android.os.ParcelFileDescriptor) {
            if (settings.noLine) {
                // (nothing plugged in: a dial gets NO DIALTONE, an answer NO CARRIER)
                NativeModem.peerEvent(if (caller) "NO LINE" else "FAILED 503")
                runCatching { line.close() }
                return
            }
            com.neodesktop.dmodem.peer.Peers.lineStarted(caller, dial, line)
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
            else -> "Ready" + (if (settings.listen) ", taking calls" else "") + when (settings.line) {
                ModemSettings.LINE_WIFI -> " (line: another phone on Wi-Fi)"
                ModemSettings.LINE_BLUETOOTH -> " (line: another phone on Bluetooth)"
                ModemSettings.LINE_CELL -> " (line: mobile calls)"
                ModemSettings.LINE_NONE -> " (no line)"
                ModemSettings.LINE_USB -> " (line: the ${usbModem?.kind?.label ?: "USB modem"})"
                else -> ""
            }
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

    /** the line's word for a caller it doesn't name */
    private fun callerOfLine(): String = when (settings.line) {
        ModemSettings.LINE_WIFI, ModemSettings.LINE_BLUETOOTH -> "The other phone"
        ModemSettings.LINE_CELL -> "A mobile call"
        ModemSettings.LINE_USB -> "A call on the phone line"
        else -> "A SIP call"
    }

    /**
     * [c] ringing: the phone's incoming call notification (heads-up, full
     * screen when the phone's locked), with Answer, Answer as fax and
     * Ignore; null: none
     */
    private fun callNotification(c: IncomingCalls.Call?) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (c == null) {
            nm.cancel(CALL_NOTIFICATION_ID)
            return
        }
        fun activity(code: Int, action: String) = PendingIntent.getActivity(
            this, code,
            Intent(this, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = activity(2, MainActivity.ACTION_INCOMING)
        val answer = activity(3, MainActivity.ACTION_ANSWER)
        val fax = activity(4, MainActivity.ACTION_ANSWER_FAX)
        val ignore = PendingIntent.getService(
            this, 5, Intent(this, ModemService::class.java).setAction(ACTION_IGNORE), PendingIntent.FLAG_IMMUTABLE,
        )
        val who = c.from ?: callerOfLine()
        val person = androidx.core.app.Person.Builder().setName(who).setImportant(true).build()
        val b = NotificationCompat.Builder(this, DModemApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_modem)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, ignore, answer))
            .setContentTitle(who)
            .setContentText("Calling the modem")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
        // (with adaptive answer, Answer takes a fax machine's call to the fax receiver by itself)
        if (!settings.adaptiveAnswer) b.addAction(0, "Answer as fax", fax)
        val n = b.build()
        try {
            nm.notify(CALL_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "call notification: $e")
        }
    }

    private fun notify(n: Notification) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // (no POST_NOTIFICATIONS: the foreground service goes on without it)
            Log.w(TAG, "notify: $e")
        }
    }

    /** whether the LAN may be reached: Android 17 asks the user (ACCESS_LOCAL_NETWORK); before, any app may */
    private fun localNetworkAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun log(line: String) {
        Log.i(TAG, line)
        ModemState.log.add("app: $line")
    }
}
