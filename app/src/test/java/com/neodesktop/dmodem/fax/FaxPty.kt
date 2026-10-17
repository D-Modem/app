// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.transfer.TransferIo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** a test's line program (native-test's), found as $[env] or in build-host; null if it isn't built */
fun nativeTool(env: String, name: String): File? = listOfNotNull(
    System.getenv(env),
    "../build-host/$name",
    "build-host/$name",
).map { File(it) }.firstOrNull { it.canExecute() }

/**
 * A line program running (its stderr and stdout into [log]), its
 * modems' ptys as it names them ("created modemA: <pty>"), each a
 * [Class1] from here.
 */
class FaxLineProcess(cmd: List<String>) : AutoCloseable {
    val log = CopyOnWriteArrayList<String>()
    private val proc: Process = ProcessBuilder(cmd).start()
    private val closers = ArrayList<() -> Unit>()

    init {
        Thread { proc.errorStream.bufferedReader().forEachLine { log.add(it) } }.apply { isDaemon = true; start() }
        Thread { proc.inputStream.bufferedReader().forEachLine { log.add(it) } }.apply { isDaemon = true; start() }
    }

    /** the pty of [name] (modemA, modemB), opened raw: our class 1 modem on it */
    fun modem(name: String, tag: String = name): Class1 {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && log.none { it.startsWith("created $name: ") }) Thread.sleep(20)
        val pty = log.firstOrNull { it.startsWith("created $name: ") }?.substringAfter(": ")
            ?: throw AssertionError("no $name\n${dump()}")
        ProcessBuilder("stty", "-F", pty, "raw", "-echo").start().waitFor()
        val i = FileInputStream(pty)
        val o = FileOutputStream(pty)
        closers.add { i.close(); o.close() }
        val io = TransferIo { b -> o.write(b); o.flush() }
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { i.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                io.feed(buf, 0, n)
            }
        }.apply { isDaemon = true; start() }
        return Class1(io) { line -> log.add("$tag: $line") }
    }

    fun dump(n: Int = 80) = log.takeLast(n).joinToString("\n")

    override fun close() {
        closers.forEach { runCatching { it() } }
        proc.destroy()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
}
