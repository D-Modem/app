// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.fax.Fax
import com.neodesktop.dmodem.modem.ModemState
import kotlinx.coroutines.delay
import kotlin.random.Random

/** what the front panel's lights show, each true when lit */
data class Lights(
    val mr: Boolean = false,
    val tr: Boolean = false,
    val cs: Boolean = false,
    val aa: Boolean = false,
    val cd: Boolean = false,
    val oh: Boolean = false,
    val rd: Boolean = false,
    val sd: Boolean = false,
    val arq: Boolean = false,
) {
    fun list() = listOf("MR" to mr, "TR" to tr, "CS" to cs, "AA" to aa, "CD" to cd, "OH" to oh, "RD" to rd, "SD" to sd, "ARQ/FAX" to arq)
}

/**
 * The modem's lights as an external 56K faxmodem's front panel had them:
 * a charcoal case, a row of small red LEDs under their legends. MR the
 * modem on, TR the DTE there (the modem's serial side open), CS ready
 * for data, AA answering calls by itself, CD the carrier, OH off hook,
 * RD and SD flickering with the octets each way, ARQ/FAX lit with error
 * correction and blinking in a fax call. [compact]: the row alone.
 */
@Composable
fun ModemLights(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.neodesktop.dmodem.settings.SettingsStore(context.applicationContext) }
    val modem by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val answering = modem?.listen == true
    val run by ModemState.run.collectAsStateWithLifecycle()
    val status by ModemState.status.collectAsStateWithLifecycle()
    val console by ModemState.console.flow.collectAsStateWithLifecycle()
    val fax by Fax.state.collectAsStateWithLifecycle()
    var rd by remember { mutableStateOf(false) }
    var sd by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(false) }

    // RD and SD: a flicker while octets go (as LEDs on a UART's lines did); ARQ/FAX's blink
    LaunchedEffect(Unit) {
        val hub = ModemState.dte
        var lastIn = hub.bytesIn
        var lastOut = hub.bytesOut
        var tick = 0
        while (true) {
            delay(45)
            val i = hub.bytesIn
            val o = hub.bytesOut
            // (state changed only when it changes: an idle panel draws nothing new)
            val r = i != lastIn && Random.nextInt(4) != 0
            val w = o != lastOut && Random.nextInt(4) != 0
            if (r != rd) rd = r
            if (w != sd) sd = w
            lastIn = i
            lastOut = o
            val b = Fax.state.value?.running == true && (++tick / 11) % 2 == 0
            if (b != blink) blink = b
        }
    }

    val on = run == ModemState.Run.RUNNING
    val online = status.link == NativeModem.Link.ONLINE
    // (error correction: the last CONNECT's LAPM or MNP)
    val lastConnect = console.lastIndexOf("CONNECT").let { if (it < 0) "" else console.substring(it).substringBefore('\n') }
    val ec = online && Regex("LAPM|MNP|V42|ARQ|REL", RegexOption.IGNORE_CASE).containsMatchIn(lastConnect)
    val faxing = fax?.running == true
    val lights = Lights(
        mr = on,
        tr = on && ModemState.dte.attached,
        cs = on,
        aa = on && answering,
        cd = on && online,
        oh = on && status.offhook,
        rd = on && rd,
        sd = on && sd,
        arq = on && (if (faxing) blink else ec),
    )
    Panel(lights, modifier, compact)
}

private val Case = Brush.verticalGradient(listOf(Color(0xff45474b), Color(0xff2a2b2e), Color(0xff1d1e20)))
private val Legend = Color(0xffd4d5d8)
private val Badge = Color(0xffeceef1)

/** the panel drawn for [lights] (the screenshots draw it too) */
@Composable
fun Panel(lights: Lights, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(if (compact) 8.dp else 18.dp)
    val described = lights.list().filter { it.second }.joinToString { it.first }.ifEmpty { "none" }
    Box(
        modifier
            .fillMaxWidth()
            .background(Case, shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x66ffffff), Color(0x11ffffff), Color(0x33000000))), shape)
            .semantics { contentDescription = "Modem lights lit: $described" },
    ) {
        Column(Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 14.dp)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("D-Modem", color = Badge, fontSize = 17.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = (-0.3).sp)
                    Spacer(Modifier.width(8.dp))
                    Text("56K V.92 FAXMODEM", color = Legend.copy(alpha = 0.75f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                // (the strip the lights sit in)
                Spacer(Modifier.height(10.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xff141516), RoundedCornerShape(if (compact) 5.dp else 9.dp))
                    .padding(vertical = if (compact) 4.dp else 8.dp, horizontal = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((label, lit) in lights.list()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label, color = Legend, fontSize = if (compact) 7.sp else 8.5.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1,
                            )
                            Spacer(Modifier.height(if (compact) 2.dp else 5.dp))
                            Led(lit, if (compact) 7.dp else 11.dp)
                        }
                    }
                }
            }
        }
    }
}

/** an LED: a dark red dome when off; lit, its glow around a bright centre */
@Composable
private fun Led(lit: Boolean, size: Dp) {
    Canvas(Modifier.size(size * 2)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = size.toPx() / 2
        if (lit) {
            drawCircle(Brush.radialGradient(listOf(Color(0x99ff2a1f), Color(0x00ff2a1f)), c, r * 2), r * 2, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xffffe4dc), Color(0xffff4b33), Color(0xffc4120c)), Offset(c.x - r * 0.25f, c.y - r * 0.25f), r * 1.1f), r, c)
        } else {
            drawCircle(Brush.radialGradient(listOf(Color(0xff6a1d1a), Color(0xff3a0d0c), Color(0xff1f0706)), Offset(c.x - r * 0.3f, c.y - r * 0.3f), r * 1.2f), r, c)
            // (its dome's highlight)
            drawCircle(Color(0x33ffffff), r * 0.28f, Offset(c.x - r * 0.35f, c.y - r * 0.35f))
        }
    }
}
