// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A phone in the tests: its modem the app's engine on a peer line
 * (native-test's peer_host: cmake -S native-test -B build-host && cmake
 * --build build-host, or DMODEM_PEER_HOST; skipped without it), its DTE
 * a socket, its line a [PeerLine] on [transport].
 */
open class TestModem(
    val name: String,
    val transport: PeerTransport,
    listen: Boolean,
    leadMs: Int,
    closers: MutableList<() -> Unit>,
) {
    val out = LinkedBlockingQueue<String>()
    val log = CopyOnWriteArrayList<String>()
    val proc: Process
    val dte: Socket
    private val dteText = StringBuffer()
    val line: PeerLine
    val events = CopyOnWriteArrayList<String>()
    /** peer_host's lines but LINE (STATUS, SPEAKER) */
    val statuses = LinkedBlockingQueue<String>()

    init {
        val bin = peerHost()
        org.junit.Assume.assumeTrue("peer_host isn't built (native-test)", bin != null)
        proc = ProcessBuilder(bin!!.path, leadMs.toString()).redirectError(File("/tmp/peer_host_$name.log"))
            .apply { environment()["PEER_SPEAKER"] = "/tmp/peer_speaker_$name.raw" }.start()
        closers.add { proc.destroyForcibly() }
        Thread {
            proc.inputStream.bufferedReader().forEachLine { l -> out.add(l) }
        }.apply { isDaemon = true; start() }
        dte = Socket("127.0.0.1", next("DTE").split(' ')[1].toInt())
        closers.add { dte.close() }
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { dte.getInputStream().read(buf) } catch (_: IOException) { -1 }
                if (n < 0) break
                dteText.append(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        }.apply { isDaemon = true; start() }
        line = PeerLine(
            transport,
            events = { e ->
                events.add(e)
                synchronized(proc) {
                    proc.outputStream.write("$e\n".toByteArray())
                    proc.outputStream.flush()
                }
            },
            target = { dial -> target(dial) },
            log = { log.add(it) },
            ringMs = 1000,
        )
        line.start(listen)
        closers.add { line.stop() }
        // the modem off hook: its line to ours
        Thread {
            while (true) {
                val l = try { out.take() } catch (_: InterruptedException) { break }
                if (l.startsWith("LINE ")) {
                    val f = l.split(' ', limit = 4)
                    val s = Socket("127.0.0.1", f[2].toInt())
                    line.lineStarted(f[1] == "1", f.getOrElse(3) { "" }, SocketDuplex(s, "modem"))
                } else {
                    statuses.add(l)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** where a dial string calls */
    open fun target(dial: String): String? = dial

    private fun next(prefix: String): String {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val l = out.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (l.startsWith(prefix)) return l
        }
        throw AssertionError("$name: no $prefix")
    }

    fun send(s: String) {
        dte.getOutputStream().write(s.toByteArray(Charsets.ISO_8859_1))
        dte.getOutputStream().flush()
    }

    /** what the DTE says until [want] (taken), in [secs] */
    fun expect(want: String, secs: Int = 60): String {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            synchronized(dteText) {
                val i = dteText.indexOf(want)
                if (i >= 0) {
                    val got = dteText.substring(0, i + want.length)
                    dteText.delete(0, i + want.length)
                    return got
                }
            }
            Thread.sleep(20)
        }
        throw AssertionError("$name: no \"$want\" in \"${dteText.toString().takeLast(300)}\"\n${log.joinToString("\n")}")
    }

    fun command(c: String, reply: String = "OK") {
        send("$c\r")
        expect(reply, 10)
    }

    /** CONNECT and the rest of its line */
    fun connectLine(): String = expect("CONNECT", 90) + expect("\r\n", 5)

    companion object {
        fun peerHost(): File? = listOfNotNull(
            System.getenv("DMODEM_PEER_HOST"),
            "../build-host/peer_host",
            "build-host/peer_host",
        ).map { File(it) }.firstOrNull { it.canExecute() }
    }
}
