// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log saved: its text (a line each), the file's name offered. */
class LogScreenTest {
    /** the saved log's text a line each, and its file's name with the date and time */
    @Test
    fun theFilesTextAndName() {
        assertEquals("app: one\ndmodem0: two\n", logText(listOf("app: one", "dmodem0: two")))
        assertEquals("", logText(emptyList()))
        val name = logFileName(java.util.Date(0))
        assertTrue(name, name.matches(Regex("dmodem-19(69|70)\\d{4}-\\d{6}\\.log")))
    }
}
