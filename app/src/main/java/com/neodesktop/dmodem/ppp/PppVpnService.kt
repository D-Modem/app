// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The dial-up link as the phone's network: a VPN whose interface has the
 * link's address and DNS servers and all IPv4 (IPv6 dropped: the link
 * has none, and apps fall back), its packets to the link and the link's
 * to it. The app's own traffic (the modem's SIP call itself) stays out of
 * it. Asked for with VpnService.prepare() first.
 */
class PppVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var out: FileOutputStream? = null
    private var reader: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ready.countDown()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            close()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // the user turned the VPN off (or another took over): the call hung up
        Internet.disconnect()
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        if (instance === this) instance = null
        ready = CountDownLatch(1)
        super.onDestroy()
    }

    private fun open(cfg: Ppp.IpConfig): Internet.Tunnel? {
        close()
        val b = Builder()
            .setSession("D-Modem dial-up")
            .setMtu(cfg.mtu)
            .addAddress(cfg.address, 32)
            .addRoute("0.0.0.0", 0)
        for (d in cfg.dns) b.addDnsServer(d)
        // (IPv6 into the VPN and dropped there: none of it around the link)
        runCatching { b.addRoute("::", 0) }
        runCatching { b.addDisallowedApplication(packageName) }
        val fd = b.establish() ?: return null
        pfd = fd
        val o = FileOutputStream(fd.fileDescriptor)
        out = o
        val input = FileInputStream(fd.fileDescriptor)
        reader = Thread({
            val buf = ByteArray(32768)
            while (true) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                // IPv4 to the link; the rest (IPv6) dropped
                if (n > 0 && (buf[0].toInt() ushr 4) == 4) Internet.sendIp(buf.copyOf(n))
            }
        }, "vpn-reader").apply { isDaemon = true; start() }
        return object : Internet.Tunnel {
            override fun write(packet: ByteArray) {
                runCatching { out?.write(packet) }
            }

            override fun close() {
                this@PppVpnService.close()
                stopSelf()
            }
        }
    }

    private fun close() {
        runCatching { pfd?.close() }
        pfd = null
        out = null
        reader = null
    }

    companion object {
        const val ACTION_STOP = "com.neodesktop.dmodem.VPN_STOP"
        @Volatile private var instance: PppVpnService? = null
        @Volatile private var ready = CountDownLatch(1)

        /** the VPN up for [cfg] (the service started first): its tunnel, null if it can't be */
        fun open(context: Context, cfg: Ppp.IpConfig): Internet.Tunnel? {
            context.startService(Intent(context, PppVpnService::class.java))
            if (!ready.await(10, TimeUnit.SECONDS)) return null
            return instance?.open(cfg)
        }
    }
}
