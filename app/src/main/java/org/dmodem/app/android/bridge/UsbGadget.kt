// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The phone's own USB port as a serial port to a computer (root only):
 * a CDC-ACM function added to the phone's USB gadget in configfs (beside
 * adb, MTP, …), so the computer sees a modem (COM3, /dev/ttyACM0), and
 * the modem served on the phone's end of it, /dev/ttyGS0. Whether a
 * phone's kernel has the function is the phone's: it says so if not.
 */
class UsbGadget(
    private val bridge: SerialBridge,
    /** a root shell's command run: its exit status and output (su -c on a phone; the tests' own) */
    private val root: (String) -> Pair<Int, String> = ::su,
    private val tty: String = TTY,
) {
    sealed interface State {
        data object Stopped : State
        /** the function there, waiting for the computer */
        data object Ready : State
        data object Connected : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var input: FileInputStream? = null

    /** the ACM function added (as root) and the tty served on a thread of its own; false, [state] saying why, if root couldn't */
    fun start(): Boolean {
        if (running) return true
        val (status, out) = root(ENABLE)
        if (status != 0) {
            _state.value = State.Failed(out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "root refused (status $status)")
            return false
        }
        running = true
        _state.value = State.Ready
        thread = Thread({ serve() }, "usb-gadget").apply { isDaemon = true; start() }
        return true
    }

    /** the tty let go, its thread joined, the function taken out again */
    fun stop() {
        if (!running) return
        running = false
        runCatching { input?.close() }
        thread?.join(2000)
        root(DISABLE)
        _state.value = State.Stopped
    }

    /** the tty served, opened again after each time the computer goes */
    private fun serve() {
        while (running) {
            val i = try { FileInputStream(tty) } catch (e: IOException) {
                _state.value = State.Failed("$tty: ${e.message}")
                running = false
                return
            }
            val o = FileOutputStream(tty)
            input = i
            val p = Port("usb", "the computer on USB", i, o) { runCatching { i.close() }; runCatching { o.close() } }
            if (bridge.tryClaim(p)) {
                _state.value = State.Connected
                bridge.serve(p, null)
            } else {
                runCatching { i.close() }; runCatching { o.close() }
            }
            if (running) {
                _state.value = State.Ready
                Thread.sleep(1000)
            }
        }
    }

    companion object {
        const val TTY = "/dev/ttyGS0"

        /**
         * an ACM function linked into the gadget's first configuration, the
         * gadget bound again (the computer sees it anew), the tty raw and
         * the app's to open
         */
        val ENABLE = """
            set -e
            G=${'$'}(ls -d /config/usb_gadget/* 2>/dev/null | head -1)
            [ -n "${'$'}G" ] || { echo "no USB gadget in configfs on this phone"; exit 2; }
            C=${'$'}(ls -d ${'$'}G/configs/* | head -1)
            mkdir -p ${'$'}G/functions/acm.gs0 2>/dev/null || { echo "this phone's kernel has no USB serial (ACM) function"; exit 3; }
            [ -e ${'$'}C/f_dmodem_acm ] || ln -s ${'$'}G/functions/acm.gs0 ${'$'}C/f_dmodem_acm
            U=${'$'}(cat ${'$'}G/UDC); [ -n "${'$'}U" ] || U=${'$'}(ls /sys/class/udc | head -1)
            echo "" > ${'$'}G/UDC; sleep 1; echo "${'$'}U" > ${'$'}G/UDC
            for i in 1 2 3 4 5; do [ -e $TTY ] && break; sleep 1; done
            [ -e $TTY ] || { echo "$TTY didn't appear"; exit 4; }
            chmod 666 $TTY
            stty -F $TTY raw -echo 2>/dev/null || true
            echo ok
        """.trimIndent()

        /** the function out of the configuration again, the gadget bound as before */
        val DISABLE = """
            G=${'$'}(ls -d /config/usb_gadget/* 2>/dev/null | head -1)
            [ -n "${'$'}G" ] || exit 0
            C=${'$'}(ls -d ${'$'}G/configs/* | head -1)
            [ -e ${'$'}C/f_dmodem_acm ] || exit 0
            rm ${'$'}C/f_dmodem_acm
            U=${'$'}(cat ${'$'}G/UDC); [ -n "${'$'}U" ] || U=${'$'}(ls /sys/class/udc | head -1)
            echo "" > ${'$'}G/UDC; sleep 1; echo "${'$'}U" > ${'$'}G/UDC
            echo ok
        """.trimIndent()

        /** whether this phone can be asked for root (an su there) */
        fun rootAvailable(): Boolean =
            listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su").any { File(it).exists() }

        /** a command as root: su -c, its status and output (stdout and stderr) */
        fun su(cmd: String): Pair<Int, String> = try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroy(); 124 to "su timed out" } else p.exitValue() to out
        } catch (e: IOException) {
            127 to "no root on this phone (su: ${e.message})"
        }
    }
}
