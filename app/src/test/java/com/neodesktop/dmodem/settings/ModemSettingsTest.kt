// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModemSettingsTest {

    @Test
    fun loginsAsDmodemdTakesThem() {
        assertEquals(Triple("alice", "secret", "sip.example.com"), ModemSettings.parseLogin("alice:secret@sip.example.com"))
        assertEquals(Triple("bob", "p@ss", "pbx.local:5070"), ModemSettings.parseLogin("bob:p@ss@pbx.local:5070"))
        assertEquals(Triple("u", "", "h"), ModemSettings.parseLogin("u:@h"))
        assertNull(ModemSettings.parseLogin("alice@sip.example.com"))
        assertNull(ModemSettings.parseLogin("alice:secret"))
        assertNull(ModemSettings.parseLogin(":x@h"))
        assertNull(ModemSettings.parseLogin("u:x@"))
    }

    @Test
    fun anAccountIsNeededToStart() {
        assertFalse(ModemSettings().complete)
        assertFalse(ModemSettings(user = "u").complete)
        assertTrue(ModemSettings(user = "u", domain = "h").complete)
    }
}
