// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.transfer.TransferIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** chat(8)'s scripts: tokens and escapes, a login, an expect's alternative, ABORT, TIMEOUT. */
class ChatScriptTest {

    /** a host: what it was sent, and its answers to it ([answer]: a line it got, what it says back) */
    private class Host(val answer: (String) -> String?) {
        val got = ByteArrayOutputStream()
        private val line = StringBuilder()
        lateinit var io: TransferIo

        init {
            io = TransferIo { b ->
                synchronized(got) { got.write(b) }
                for (c in String(b, Charsets.ISO_8859_1)) {
                    if (c == '\r') {
                        val l = line.toString()
                        line.setLength(0)
                        answer(l)?.let { a -> Thread { Thread.sleep(20); io.feed(a.toByteArray(Charsets.ISO_8859_1)) }.start() }
                    } else {
                        line.append(c)
                    }
                }
            }
        }

        /** [s] said by the host, unasked */
        fun say(s: String) = io.feed(s.toByteArray(Charsets.ISO_8859_1))
        /** all the host was sent, as text */
        fun sent() = synchronized(got) { String(got.toByteArray(), Charsets.ISO_8859_1) }
    }

    /** a script split into its tokens: quotes, escapes, a comment and newlines between */
    @Test
    fun tokensAndEscapes() {
        val c = ChatScript("ABORT 'NO CARRIER' \"\" ATZ\nogin:--ogin: \\U  'word: ' \\P\n# a comment\n\"\" ^M\\c", "alice", "pw")
        assertEquals(listOf("ABORT", "NO CARRIER", "", "ATZ", "ogin:--ogin:", "\\U", "word: ", "\\P", "", "^M\\c"), c.tokens())
    }

    /** a login through its prompts (ogin:--ogin:'s return first), the user and password sent, the password kept out of the log */
    @Test
    fun aLoginWithItsPrompts() {
        // the host says its banner and "login:" only after a return (ogin:--ogin: sends one)
        val h = Host { l ->
            when (l) {
                "" -> "\r\nWelcome to the BBS ISP\r\nlogin: "
                "alice" -> "Password: "
                "s3cret" -> "\r\nStarting PPP...\r\n"
                else -> "\r\nLogin incorrect\r\n"
            }
        }
        val log = ArrayList<String>()
        ChatScript("TIMEOUT 2 ABORT incorrect ogin:--ogin: \\U assword: \\P PPP", "alice", "s3cret").run(h.io) { log.add(it) }
        assertEquals("\ralice\rs3cret\r", h.sent())
        // (the password not in the log)
        assertTrue(log.toString(), log.none { it.contains("s3cret") } && log.any { it.contains("(the password)") })
    }

    /** an ABORT string seen: its IOException; a quiet host: TIMEOUT's, after about its seconds */
    @Test
    fun abortAndTimeout() {
        val h = Host { l -> if (l == "alice") "Password: " else "\r\nLogin incorrect\r\n" }
        h.say("login: ")
        val e = runCatching { ChatScript("ABORT incorrect ogin: \\U assword: \\P PPP", "alice", "wrong").run(h.io) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("incorrect"))

        val quiet = Host { null }
        val t0 = System.currentTimeMillis()
        val e2 = runCatching { ChatScript("TIMEOUT 1 ogin: \\U", "a", "b").run(quiet.io) }.exceptionOrNull()
        assertTrue("$e2", e2 is IOException && e2.message!!.contains("ogin:"))
        assertTrue(System.currentTimeMillis() - t0 in 900..3000)
    }

    /** ^C as a control character, \c for no return, \t as a tab */
    @Test
    fun controlCharactersAndNoReturn() {
        val h = Host { null }
        ChatScript("\"\" ^C\\c \"\" 'a b\\c' \"\" x\\ty", "", "").run(h.io)
        assertEquals("\u0003a bx\ty\r", h.sent())
    }
}
