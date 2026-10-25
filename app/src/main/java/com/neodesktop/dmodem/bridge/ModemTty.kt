// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.neodesktop.dmodem.local.Pty
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The modem as a serial port for the local Linux: /dev/ttyDM0 there, a
 * pty here (its slave bound in by proot). A program that opens it has the
 * modem as a client of the serial bridges has it ([SerialBridge]): one at
 * a time, the modem's DTE to it and back; it closing the port is DTR
 * dropped (the call hung up), as a serial cable's end would be. While no
 * program has it open, nothing goes to it.
 */
class ModemTty(
    private val bridge: SerialBridge,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var master = -1
    @Volatile private var kicked = false

    /** the pty's slave here (/dev/pts/N), made the first time, or null if it can't be */
    @Synchronized
    fun slave(): String? {
        if (master < 0) {
            val fd = try { Pty.openPort() } catch (e: IOException) { log("ttyDM0: ${e.message}"); return null } catch (_: UnsatisfiedLinkError) { return null }
            master = fd
            Thread({ watch(fd) }, "ttyDM0").apply { isDaemon = true; start() }
        }
        return Pty.ptsName(master)
    }

    /** whether a program has the port open now */
    fun open(): Boolean = master >= 0 && (Pty.poll(master, 0) and Pty.POLLHUP) == 0

    /** each program that opens the port served until it closes it */
    private fun watch(fd: Int) {
        while (true) {
            val ev = Pty.poll(fd, 500)
            if (ev and Pty.POLLHUP != 0 && ev and Pty.POLLIN == 0) {
                // (no program has it: poll says so at once, so wait between looks)
                kicked = false
                Thread.sleep(200)
                continue
            }
            // (a program has it: even one that hasn't sent anything yet, waiting for RING)
            if (kicked) {
                // (let go of by the bridge: what it sends dropped until it closes)
                drain(fd)
                kicked = false
                continue
            }
            val port = Port("tty", "ttyDM0", Input(fd), Output(fd)) { kicked = true }
            if (!bridge.tryClaim(port)) {
                // (the modem is another client's: what the program sends goes nowhere until it closes)
                log("ttyDM0 opened while ${bridge.client.value?.name ?: "another client"} has the modem")
                drain(fd)
                continue
            }
            log("ttyDM0 opened: the modem is the local Linux's")
            bridge.serve(port, null)
            log("ttyDM0 closed")
        }
    }

    /** what comes read and dropped until no program has the port */
    private fun drain(fd: Int) {
        val buf = ByteArray(4096)
        while (true) {
            val ev = Pty.poll(fd, 500)
            if (ev and Pty.POLLIN != 0) {
                if (Pty.read(fd, buf, 0, buf.size) < 0) return
            } else if (ev and Pty.POLLHUP != 0) {
                return
            }
        }
    }

    /** the program's octets: -1 once it has closed the port (or the bridge let it go) */
    private inner class Input(private val fd: Int) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (kicked) return -1
                val ev = Pty.poll(fd, 250)
                if (ev and Pty.POLLIN != 0) return Pty.read(fd, b, off, len)
                if (ev and Pty.POLLHUP != 0) return -1
            }
        }
    }

    /** the modem's octets to the program */
    private class Output(private val fd: Int) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!Pty.write(fd, b, off, len)) throw IOException("ttyDM0: the write failed")
        }
    }

    companion object {
        /** where it is in the local Linux */
        const val PATH = "/dev/ttyDM0"
    }
}
