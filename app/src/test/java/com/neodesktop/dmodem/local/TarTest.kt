// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/** Tars as the system's tar makes them (GNU and pax), unpacked; and ones made to escape, refused. */
class TarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun tar(dir: File, format: String): File {
        assumeTrue("no tar here", File("/usr/bin/tar").canExecute() || File("/bin/tar").canExecute())
        val out = File(tmp.root, "t-$format.tar")
        val p = ProcessBuilder("tar", "--format=$format", "-cf", out.path, "-C", dir.path, ".").redirectErrorStream(true).start()
        val msg = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        assertEquals(msg, 0, p.exitValue())
        return out
    }

    @Test
    fun aTreeAsTarMadeIt() {
        val src = tmp.newFolder("src")
        File(src, "bin").mkdirs()
        val busybox = File(src, "bin/busybox").apply { writeText("#!fake busybox\n") }
        Files.setPosixFilePermissions(busybox.toPath(), PosixFilePermission.values().toSet() - PosixFilePermission.OTHERS_WRITE - PosixFilePermission.GROUP_WRITE)
        Files.createSymbolicLink(File(src, "bin/sh").toPath(), Paths.get("/bin/busybox"))
        Files.createSymbolicLink(File(src, "bin/ls").toPath(), Paths.get("busybox"))
        Files.createLink(File(src, "bin/hard").toPath(), busybox.toPath())
        val long = "a".repeat(80) + "/" + "b".repeat(80) + "/" + "c".repeat(90) + ".txt"
        File(src, long).apply { parentFile!!.mkdirs(); writeText("long\n") }
        File(src, "etc").mkdirs()
        File(src, "etc/secret").apply { writeText("x") }.also { Files.setPosixFilePermissions(it.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        File(src, "big.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(src, "empty").mkdirs()

        for (format in listOf("gnu", "pax", "ustar".takeIf { false }).filterNotNull()) {
            val dest = File(tmp.root, "out-$format")
            val stats = Tar.extract(tar(src, format).inputStream(), dest)
            assertEquals(format, "#!fake busybox\n", File(dest, "bin/busybox").readText())
            assertTrue(format, File(dest, "bin/busybox").canExecute())
            assertEquals("/bin/busybox", Files.readSymbolicLink(File(dest, "bin/sh").toPath()).toString())
            assertEquals("busybox", Files.readSymbolicLink(File(dest, "bin/ls").toPath()).toString())
            assertEquals("#!fake busybox\n", File(dest, "bin/hard").readText())
            assertEquals("long\n", File(dest, long).readText())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(File(dest, "etc/secret").toPath()))
            assertTrue(File(dest, "big.bin").readBytes().contentEquals(File(src, "big.bin").readBytes()))
            assertTrue(File(dest, "empty").isDirectory)
            assertTrue(stats.files >= 4 && stats.links >= 3)
        }
    }

    /** a tar written by hand: entries the system's tar wouldn't make */
    private class Writer {
        val out = ByteArrayOutputStream()
        fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "") {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644\u0000".toByteArray().copyInto(h, 100)
            "%011o\u0000".format(data.size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte()
            link.toByteArray().copyInto(h, 157)
            "ustar\u000000".toByteArray().copyInto(h, 257)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            "%06o\u0000 ".format(h.sumOf { it.toInt() and 0xff }).toByteArray().copyInto(h, 148)
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        fun bytes() = out.toByteArray() + ByteArray(1024)
    }

    @Test
    fun nothingWrittenOutside() {
        val outside = tmp.newFolder("outside")
        val dest = File(tmp.root, "dest")
        val w = Writer()
        w.entry("../escaped", '0', "no".toByteArray())
        w.entry("/abs/ok", '0', "inside".toByteArray())
        // a link to outside, then a file "through" it
        w.entry("evil", '2', link = outside.path)
        w.entry("evil/planted", '0', "no".toByteArray())
        w.entry("hard", '1', link = "../../etc/passwd")
        runCatching { Tar.extract(w.bytes().inputStream(), dest) }
        assertFalse(File(tmp.root, "escaped").exists())
        assertEquals("absolute names kept inside", "inside", File(dest, "abs/ok").readText())
        assertFalse("nothing through the link", File(outside, "planted").exists())
        assertTrue("the link itself is there", Files.isSymbolicLink(File(dest, "evil").toPath()))
        assertFalse(File(dest, "hard").exists())
    }

    @Test
    fun removedWithoutFollowingLinks() {
        val outside = tmp.newFolder("storage")
        val keep = File(outside, "photo.jpg").apply { writeText("keep me") }
        val sys = tmp.newFolder("system")
        File(sys, "rootfs/usr/bin").mkdirs()
        File(sys, "rootfs/usr/bin/x").writeText("x")
        Files.createSymbolicLink(File(sys, "rootfs/sdcard").toPath(), outside.toPath())
        Files.createSymbolicLink(File(sys, "rootfs/photo").toPath(), keep.toPath())
        // a read-only directory, as packages make them
        File(sys, "rootfs/ro").mkdirs()
        File(sys, "rootfs/ro/f").writeText("f")
        File(sys, "rootfs/ro").setWritable(false)
        Tar.removeTree(sys)
        assertFalse(sys.exists())
        assertEquals("keep me", keep.readText())
    }
}
