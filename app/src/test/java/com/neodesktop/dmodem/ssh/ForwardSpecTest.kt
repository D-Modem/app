// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The forwards' lines, as ssh's -L, -R and -D take them. */
class ForwardSpecTest {
    /** -L, -R and -D lines parsed as ssh takes them (IPv6 in brackets), and written back the same */
    @Test
    fun linesAsSshTakesThem() {
        val l = ForwardSpec.parse("L 8080:localhost:80")!!
        assertEquals(ForwardSpec(ForwardSpec.Type.LOCAL, null, 8080, "localhost", 80), l)
        assertEquals("L 8080:localhost:80", l.toString())
        assertEquals(ForwardSpec(ForwardSpec.Type.LOCAL, "0.0.0.0", 5432, "db.internal", 5432), ForwardSpec.parse("-L 0.0.0.0:5432:db.internal:5432"))
        assertEquals(ForwardSpec(ForwardSpec.Type.REMOTE, null, 2222, "localhost", 22), ForwardSpec.parse("R 2222:localhost:22"))
        assertEquals(ForwardSpec(ForwardSpec.Type.DYNAMIC, null, 1080), ForwardSpec.parse("D 1080"))
        assertEquals(ForwardSpec(ForwardSpec.Type.DYNAMIC, "*", 1080), ForwardSpec.parse("-D *:1080"))
        // IPv6 in brackets, both ways
        val v6 = ForwardSpec.parse("L [::1]:8080:[fe80::1]:80")!!
        assertEquals(ForwardSpec(ForwardSpec.Type.LOCAL, "::1", 8080, "fe80::1", 80), v6)
        assertEquals("L [::1]:8080:[fe80::1]:80", v6.toString())
        for (s in listOf(l, v6, ForwardSpec.parse("D *:1080")!!, ForwardSpec.parse("R 0.0.0.0:9000:10.0.0.2:9000")!!)) {
            assertEquals(s, ForwardSpec.parse(s.toString()))
        }
    }

    /** lines that aren't forwards: null */
    @Test
    fun notForwards() {
        for (bad in listOf("", "X 1:a:2", "L 8080", "L 8080:host", "L 99999:a:1", "L a:b:c", "D", "D 1:2:3", "L 1::2", "R [::1:8080:a:1")) {
            assertNull(bad, ForwardSpec.parse(bad))
        }
    }
}
