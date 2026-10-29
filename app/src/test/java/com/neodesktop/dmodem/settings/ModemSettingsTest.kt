// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModemSettingsTest {

    /** user:password@host logins as dmodemd takes them (an @ in the password, a port), the others not */
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

    /** settings without a user and domain aren't complete */
    @Test
    fun anAccountIsNeededToStart() {
        assertFalse(ModemSettings().complete)
        assertFalse(ModemSettings(user = "u").complete)
        assertTrue(ModemSettings(user = "u", domain = "h").complete)
    }

    /** a USB modem needs no SIP account and is no peer line; the other lines as they were */
    @Test
    fun aUsbModemNeedsNoAccountAndIsNoPeer() {
        val s = ModemSettings(line = ModemSettings.LINE_USB)
        assertTrue(s.complete)
        assertTrue(s.usb)
        assertFalse(s.peer)
        assertFalse(s.sip)
        assertFalse(s.noLine)
        // (the others as they were)
        assertTrue(ModemSettings(line = ModemSettings.LINE_WIFI).peer)
        assertTrue(ModemSettings(line = ModemSettings.LINE_NONE).peer)
        assertTrue(ModemSettings().sip)
        assertFalse(ModemSettings().usb)
    }

    /** a USB line's transmit level: auto by default, else its gain, no lower than -20; none when not USB */
    @Test
    fun aUsbLinesTransmitLevelIsAutomaticByDefault() {
        val usb = ModemSettings(line = ModemSettings.LINE_USB)
        assertEquals("auto", usb.usbTxGainEnv)
        assertEquals("6", usb.copy(usbTxAuto = false, usbTxGain = 6).usbTxGainEnv)
        assertEquals("-20", usb.copy(usbTxAuto = false, usbTxGain = -40).usbTxGainEnv)
        assertNull(usb.copy(usbTxAuto = false).usbTxGainEnv)
        assertNull(ModemSettings().usbTxGainEnv)
    }
}
