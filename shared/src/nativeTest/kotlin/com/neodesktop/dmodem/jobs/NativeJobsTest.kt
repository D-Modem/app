// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.jobs

import com.neodesktop.dmodem.io.ByteQueue
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.read
import com.neodesktop.dmodem.io.sleepMs
import com.neodesktop.dmodem.ppp.TunnelStarter
import com.neodesktop.dmodem.ppp.UdpPacketTunnel
import com.neodesktop.dmodem.transfer.PosixFiles
import com.neodesktop.dmodem.transfer.PosixFolderIncoming
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.getpid
import platform.posix.recv
import platform.posix.rmdir
import platform.posix.sendto
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What iOS has natively for its jobs: files by path (sent, received into
 * a folder, names made safe and unique), and the dial-up link's packets
 * to the packet tunnel as UDP on the loopback.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeJobsTest {
    @Test
    fun filesInAFolder() {
        val dir = "/tmp/dmodem-files-${getpid()}"
        val into = PosixFolderIncoming(dir)
        val (s1, n1) = into.create("../evil/a:b.txt", 5)
        s1.write("hello".encodeToByteArray())
        s1.close()
        into.done(n1, 1_700_000_000)
        assertEquals("a_b.txt", n1)
        val (s2, n2) = into.create("a:b.txt", 5)
        s2.write("again".encodeToByteArray())
        s2.close()
        assertEquals("a_b (2).txt", n2)
        val out = PosixFiles.outgoing("$dir/$n1")!!
        assertEquals("a_b.txt", out.name)
        assertEquals(5L, out.size)
        val src = out.open()
        val b = ByteArray(16)
        val n = src.read(b, 0, b.size)
        src.close()
        assertContentEquals("hello".encodeToByteArray(), b.copyOf(n))
        into.abandon(n2)
        assertTrue(!PosixFiles.exists("$dir/$n2"))
        unlink("$dir/$n1")
        rmdir(dir)
        assertEquals(-1L, PosixFiles.size("$dir/none"))
        assertEquals(null, PosixFiles.outgoing("$dir/none"))
    }

    @Test
    fun packetsOverTheLoopback() {
        // the link's job (never connected: its packets only counted), the tunnel's "extension" a UDP socket
        val job = DialUpJob({ true }, { }, { })
        var started = ""
        var stopped = false
        val t = UdpPacketTunnel(job, object : TunnelStarter {
            override fun start(address: String, dns: String, mtu: Int, port: Int): Boolean {
                started = "$address $dns $mtu $port"
                return true
            }
            override fun stop() { stopped = true }
        })
        assertTrue(t.port > 0)
        assertTrue(t.up("10.0.0.2", listOf("1.1.1.1", "8.8.8.8"), 1500))
        assertEquals("10.0.0.2 1.1.1.1,8.8.8.8 1500 ${t.port}", started)
        val ext = socket(AF_INET, SOCK_DGRAM, 0)
        memScoped {
            val a = alloc<sockaddr_in>()
            a.sin_family = AF_INET.convert()
            a.sin_port = (((t.port and 0xff) shl 8) or (t.port shr 8)).toUShort()
            // (127.0.0.1 in network order, on a little-endian host)
            a.sin_addr.s_addr = 0x0100007fu
            // a hello (where it is), then a packet: to the link (not up: dropped there, but counted)
            val hello = ByteArray(1)
            hello.usePinned { sendto(ext, it.addressOf(0), 1u, 0, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }
            val pkt = ByteArray(40) { it.toByte() }
            pkt.usePinned { sendto(ext, it.addressOf(0), 40u, 0, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }
        }
        val end = monotonicMs() + 5000
        while (t.fromTunnel.value < 1 && monotonicMs() < end) sleepMs(20)
        assertEquals(1, t.fromTunnel.value)
        // a packet from the link: to the extension
        val down = ByteArray(60) { (it * 3).toByte() }
        t.packet(down)
        val got = ByteArray(2000)
        val n = got.usePinned { recv(ext, it.addressOf(0), 2000u, 0) }.toInt()
        assertContentEquals(down, got.copyOf(n))
        t.close()
        assertTrue(stopped)
        platform.posix.close(ext)
    }
}
