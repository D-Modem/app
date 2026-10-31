// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The native library on a device: it loads, its natives and callbacks
 * bind, the modem answers AT commands on its DTE, and it stops. No call
 * (that needs a SIP peer: native-test does that on the build host).
 */
@RunWith(AndroidJUnit4::class)
class NativeModemTest {

    private val logs = CopyOnWriteArrayList<String>()
    private val exits = CopyOnWriteArrayList<Int>()
    private val exited = CountDownLatch(1)

    /** the modem stopped and the listener gone after each test */
    @After
    fun tearDown() {
        NativeModem.stop()
        NativeModem.listener = null
    }

    /**
     * a SIP account at nowhere (no call is made), the modem's data in the
     * app's files; SIP's log at level 4, which names its addresses
     * ("SIP on ...") before start returns: at 3 a modem with no call and
     * no registrar has nothing to say in the test's time
     */
    private fun config() = NativeModem.Config(
        user = "test", password = "x", domain = "127.0.0.1:5999", sipPort = 15060,
        dns = listOf("127.0.0.1"), logLevel = 1, sipLogLevel = 4,
        dataFile = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test_dsp").path,
    )

    /** [cmd] sent on [dte]: what came back by the time [expect] matched or [secs] passed */
    private fun command(dte: ParcelFileDescriptor, cmd: String, expect: Regex, secs: Int = 10): String {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(dte.dup())
        out.write("$cmd\r".toByteArray())
        out.flush()
        val input = ParcelFileDescriptor.AutoCloseInputStream(dte.dup())
        val buf = StringBuilder()
        val end = System.currentTimeMillis() + secs * 1000
        val b = ByteArray(1024)
        while (System.currentTimeMillis() < end && !expect.containsMatchIn(buf)) {
            if (input.available() > 0) {
                val n = input.read(b)
                if (n < 0) break
                buf.append(String(b, 0, n, Charsets.ISO_8859_1))
            } else {
                Thread.sleep(20)
            }
        }
        out.close()
        input.close()
        return buf.toString()
    }

    /** the modem starts (once at a time), answers AT, ATI3 and ATX3, and stops with 0, SIP's log through the hook */
    @Test
    fun startsAnswersAndStops() {
        NativeModem.listener = object : NativeModem.Listener {
            /** the line kept */
            override fun onLog(line: String) { logs.add(line) }
            /** the result kept, the test woken */
            override fun onExit(result: Int) { exits.add(result); exited.countDown() }
        }
        val dte = NativeModem.start(config())
        assertNotNull("the modem started", dte)
        assertTrue(NativeModem.running)
        assertNull("one modem at a time", NativeModem.start(config()))

        val ok = command(dte!!, "AT", Regex("OK"))
        assertTrue("AT: $ok", ok.contains("OK"))
        val i3 = command(dte, "ATI3", Regex("OK"))
        assertTrue("ATI3 names the line: $i3", i3.contains("SIP"))
        val x3 = command(dte, "ATX3", Regex("OK"))
        assertTrue(x3.contains("OK"))

        NativeModem.stop()
        assertTrue(exited.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(0), exits.toList())
        assertFalse(NativeModem.running)
        assertTrue("SIP logged through the hook: $logs", logs.any { it.startsWith("sip: ") })
        dte.close()
    }

    /** closing the DTE stops the modem, with 0 */
    @Test
    fun closingTheDteStopsTheModem() {
        NativeModem.listener = object : NativeModem.Listener {
            /** the result kept, the test woken */
            override fun onExit(result: Int) { exits.add(result); exited.countDown() }
        }
        val dte = NativeModem.start(config())
        assertNotNull(dte)
        assertTrue(command(dte!!, "AT", Regex("OK")).contains("OK"))
        dte.close()
        assertTrue(exited.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(0), exits.toList())
        assertFalse(NativeModem.running)
    }
}
