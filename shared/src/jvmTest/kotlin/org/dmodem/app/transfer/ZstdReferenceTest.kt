// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Our zstd against the reference zstd (its command, where it is
 * installed): what it makes we decode, what we make it decodes.
 */
class ZstdReferenceTest {
    private fun zstd(vararg args: String, input: ByteArray): ByteArray? {
        val p = try {
            ProcessBuilder(listOf("zstd") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) {
            return null
        }
        val out = Thread { p.outputStream.use { it.write(input) } }.also { it.start() }
        val result = p.inputStream.readBytes()
        out.join()
        return if (p.waitFor() == 0) result else null
    }

    @Test
    fun bothWays() {
        val r = Random(11)
        val words = "the modem carrier BBS file area sysop message ZMODEM upload download\r\n".split(' ')
        val text = buildString { while (length < 200_000) append(words[r.nextInt(words.size)]).append(' ') }.encodeToByteArray()
        for (data in listOf(text.copyOf(64 * 1024), text, r.nextBytes(70_000), ByteArray(0))) {
            for (level in listOf("-1", "-19")) {
                val theirs = zstd("-q", level, "--content-size", "--check", "-c", input = data) ?: return
                assertContentEquals(data, Zstd.decode(theirs, 1 shl 23), "theirs $level, ${data.size} bytes")
            }
            val back = zstd("-q", "-d", "-c", input = Zstd.encode(data)) ?: return
            assertContentEquals(data, back, "ours, ${data.size} bytes")
        }
    }
}
