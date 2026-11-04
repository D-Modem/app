// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import org.dmodem.app.android.local.Pty
import java.io.IOException

/**
 * A USB serial adapter plugged into the phone as a serial port for the local Linux: /dev/ttyUSB0 there,
 * a pty here (its slave bound in by proot, as [ModemTty]'s is). Android lets no app's programs at the
 * USB device itself, so the app opens it ([open]) when a program opens the port, and carries the octets
 * between the two until the program closes it (the adapter closed then: its DTR drops, as a real port's
 * HUPCL would). What the program sets its port to goes to the adapter as it changes: the speed, the stop
 * bits, RTS/CTS, and B0's hang-up (DTR down). A pty keeps no character size or parity: the adapter is
 * 8 data bits, no parity. While no program has the port, the adapter is free for the app's own uses.
 */
class SerialTty(
    /** where it is in the local Linux */
    private val path: String,
    /** an adapter opened at a format, blocking (Android may ask the user first); null if there's none (said to the log) */
    private val open: (SerialFormat) -> SerialLine?,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var master = -1

    /** the pty's slave here (/dev/pts/N), made the first time, or null if it can't be */
    @Synchronized
    fun slave(): String? {
        if (master < 0) {
            val fd = try { Pty.openPort() } catch (e: IOException) { log("$path: ${e.message}"); return null } catch (_: UnsatisfiedLinkError) { return null }
            master = fd
            Thread({ watch(fd) }, "serial-tty").apply { isDaemon = true; start() }
        }
        return Pty.ptsName(master)
    }

    /** whether a program has the port open now */
    fun inUse(): Boolean = master >= 0 && (Pty.poll(master, 0) and Pty.POLLHUP) == 0

    /** each program that opens the port given the adapter until it closes it */
    private fun watch(fd: Int) {
        while (true) {
            val ev = Pty.poll(fd, 500)
            if (ev and Pty.POLLHUP != 0 && ev and Pty.POLLIN == 0) {
                // (no program has it: poll says so at once, so wait between looks)
                Thread.sleep(200)
                continue
            }
            val t = Settings.of(Pty.termios(fd))
            val line = try { open(t.format) } catch (e: Exception) { log("$path: ${e.message}"); null }
            if (line == null) {
                // (no adapter: what the program sends goes nowhere until it closes the port)
                drain(fd)
                continue
            }
            log("$path opened: ${line.name} at ${t.format}" + if (t.rtsCts) ", RTS/CTS" else "")
            serve(fd, line, t)
            log("$path closed: ${line.name} let go")
        }
    }

    /** [line] carried to and from the program on the pty until it closes it (or the adapter goes) */
    private fun serve(fd: Int, line: SerialLine, first: Settings) {
        val gone = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            apply(line, null, first)
            val reader = Thread({
                val buf = ByteArray(4096)
                try {
                    while (!gone.get()) {
                        val n = line.read(buf, 200)
                        if (n > 0 && !Pty.write(fd, buf, 0, n)) break
                    }
                } catch (_: IOException) {
                    if (!gone.get()) log("$path: ${line.name} is gone (unplugged?)")
                }
                gone.set(true)
            }, "serial-tty-in").apply { isDaemon = true; start() }
            val buf = ByteArray(4096)
            var now = first
            while (!gone.get()) {
                val ev = Pty.poll(fd, 200)
                if (ev and Pty.POLLIN != 0) {
                    val n = Pty.read(fd, buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) line.write(buf, 0, n)
                } else if (ev and Pty.POLLHUP != 0) {
                    break
                }
                // (the program's stty, as it changes)
                val next = Settings.of(Pty.termios(fd), now)
                if (next != now) {
                    apply(line, now, next)
                    now = next
                }
            }
            val unplugged = gone.getAndSet(true)
            line.close()
            reader.join(1000)
            // (the adapter gone with the program still on the port: nothing more until it closes it)
            if (unplugged) drain(fd)
        } catch (e: IOException) {
            // (a write to it failed: unplugged)
            gone.set(true)
            log("$path: ${line.name}: ${e.message}")
            line.close()
            drain(fd)
        } finally {
            gone.set(true)
            line.close()
        }
    }

    /** what the program set ([next]) to the adapter: what differs from [was] (all of it the first time) */
    private fun apply(line: SerialLine, was: Settings?, next: Settings) {
        if (was == null || was.format != next.format) {
            // (a speed the adapter can't do: kept at the one before)
            runCatching { line.setFormat(next.format) }.onFailure { log("$path: ${next.format.baud} bit/s: ${it.message}") }
        }
        if (was == null || was.rtsCts != next.rtsCts) {
            if (!line.setRtsCts(next.rtsCts)) log("$path: ${line.name} has no RTS/CTS flow control of its own")
        }
        if (was == null || was.dtr != next.dtr) {
            // (B0 is a hang-up: DTR down; a speed again, up)
            runCatching { line.setDtr(next.dtr) }
            runCatching { line.setRts(next.dtr) }
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

    /** what a program set its port to (Pty.termios) */
    internal data class Settings(val format: SerialFormat, val rtsCts: Boolean, val dtr: Boolean) {
        companion object {
            /** from Pty.termios' answer [t] (null: 9600 8N1, as a Linux port starts), [was] what it was before */
            fun of(t: IntArray?, was: Settings? = null): Settings {
                if (t == null || t.size < 4) return was ?: Settings(SerialFormat(9600), rtsCts = false, dtr = true)
                // (B0, or a rate the table doesn't name: the speed it had)
                val baud = if (t[0] > 0) t[0] else was?.format?.baud ?: 9600
                return Settings(SerialFormat(baud, 8, 'N', if (t[1] == 2) 2 else 1), rtsCts = t[2] != 0, dtr = t[3] != 0)
            }
        }
    }

    companion object {
        /** where it is in the local Linux */
        const val PATH = "/dev/ttyUSB0"
    }
}
