// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.bridge.Bridges
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.ppp.Internet
import org.dmodem.app.android.settings.BridgeSettings
import org.dmodem.app.android.settings.BridgeSettingsStore
import org.dmodem.app.android.settings.IspSettingsStore
import org.dmodem.app.android.settings.ModemSettings
import org.dmodem.app.path.ConnectionPath

/**
 * The connection path at the top of Settings, as PCOMM's "Connection
 * Overview" draws its link: each step's role above its icon, what it is
 * below, one line joining them. Idle, it shows how it's all set up (a step
 * still to set in the error colour, its line dashed); starting, dialling and
 * online, how it is now. A step opens its settings page.
 */
@Composable
fun ConnectionPathCard(s: ModemSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val run by ModemState.run.collectAsStateWithLifecycle()
    val status by ModemState.status.collectAsStateWithLifecycle()
    val internet by Internet.state.collectAsStateWithLifecycle()
    val bridges by remember { BridgeSettingsStore(context.applicationContext).settings }
        .collectAsStateWithLifecycle(BridgeSettings())
    var isp by remember { mutableStateOf(false) }
    var dialIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        IspSettingsStore(context.applicationContext).settings.collect { isp = it.complete; dialIn = it.dialIn }
    }
    // (a USB modem's line: whether one is plugged in)
    val usbPlugged = if (s.usb) rememberUsbModemsPlugged() else null

    val path = ConnectionPath.of(
        ConnectionPath.Config(
            line = lineOf(s),
            sipAnonymous = s.anonymous,
            sipUser = s.user,
            sipDomain = s.domain,
            listen = s.listen,
            peerName = s.peerName,
            peerAddress = s.peerAddress,
            btModemName = s.btModemName,
            btModemAddress = s.btModemAddress,
            serialBaud = s.serialModemBaud,
            serialRtsCts = s.serialModemRtsCts,
            usbDevice = usbPlugged?.let { if (it == "none") "" else it },
            cellSpeed = s.cellSpeed,
            isp = isp,
            dialIn = dialIn,
            bridges = bridgeNames(bridges),
        ),
        ConnectionPath.Live(
            run = ConnectionPath.Run.valueOf(run.name),
            call = ConnectionPath.Call.valueOf(status.link.name),
            rate = rateOf(status),
            internet = internet?.connected == true,
        ),
    )
    ConnectionPathView(path, modifier)
}

/** [path] drawn: a step's tap opens its settings page */
@Composable
fun ConnectionPathView(path: ConnectionPath.Path, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = false) {},
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                path.steps.forEachIndexed { i, step ->
                    StepColumn(
                        step,
                        left = if (i > 0) path.link(i - 1) else null,
                        right = if (i < path.steps.size - 1) path.link(i) else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val todo = path.steps.any { it.state == ConnectionPath.State.SETUP }
            Text(
                path.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (todo) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/** a step: its role, its icon on the line (its links to either side), its name and what it is */
@Composable
private fun StepColumn(step: ConnectionPath.Step, left: ConnectionPath.State?, right: ConnectionPath.State?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = stateColor(step.state)
    val leftColor = left?.let { stateColor(it) }
    val rightColor = right?.let { stateColor(it) }
    val dashLeft = left == ConnectionPath.State.SETUP || left == ConnectionPath.State.OFF
    val dashRight = right == ConnectionPath.State.SETUP || right == ConnectionPath.State.OFF
    Column(
        modifier
            .clickable { SettingsNav.open(pageOf(step.page)) }
            .clearAndSetSemantics { contentDescription = "${step.role}: ${step.title}, ${step.detail}" }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            step.role, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // (the line through the icons' middles: half to the step before, half to the next)
        Box(
            Modifier.fillMaxWidth().height(48.dp).drawBehind {
                val y = size.height / 2
                val w = 2.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                if (leftColor != null) drawLine(leftColor, Offset(0f, y), Offset(size.width / 2, y), w, pathEffect = if (dashLeft) dash else null)
                if (rightColor != null) drawLine(rightColor, Offset(size.width / 2, y), Offset(size.width, y), w, pathEffect = if (dashRight) dash else null)
            },
            contentAlignment = Alignment.Center,
        ) {
            val active = step.state == ConnectionPath.State.ACTIVE
            Box(
                Modifier.size(48.dp)
                    .background(if (active) colors.primaryContainer else colors.surface, CircleShape)
                    .border(2.dp, tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconOf(step.kind), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            step.title, style = MaterialTheme.typography.labelLarge, color = colors.onSurface,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            step.detail, style = MaterialTheme.typography.bodySmall,
            color = if (step.state == ConnectionPath.State.SETUP) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** a state's colour: ready the primary, to set the error's, off faint, working and active the tertiary */
@Composable
private fun stateColor(s: ConnectionPath.State): Color {
    val c = MaterialTheme.colorScheme
    return when (s) {
        ConnectionPath.State.READY -> c.primary
        ConnectionPath.State.SETUP -> c.error
        ConnectionPath.State.OFF -> c.outlineVariant
        ConnectionPath.State.WORKING -> c.tertiary
        ConnectionPath.State.ACTIVE -> c.tertiary
    }
}

/** a step's icon */
private fun iconOf(k: ConnectionPath.Kind): ImageVector = when (k) {
    ConnectionPath.Kind.TERMINAL -> SettingsIcons.Terminal
    ConnectionPath.Kind.PPP -> SettingsIcons.Internet
    ConnectionPath.Kind.SOFTMODEM, ConnectionPath.Kind.MODEM -> SettingsIcons.Modem
    ConnectionPath.Kind.SERIAL, ConnectionPath.Kind.USB -> SettingsIcons.Bridges
    ConnectionPath.Kind.BLUETOOTH -> SettingsIcons.Bluetooth
    ConnectionPath.Kind.WIFI -> SettingsIcons.Wifi
    ConnectionPath.Kind.SIP, ConnectionPath.Kind.CELL -> Icons.Filled.Call
    ConnectionPath.Kind.SIP_ADDRESS -> Icons.Filled.Send
    ConnectionPath.Kind.PHONE_NETWORK, ConnectionPath.Kind.PHONE_LINE -> SettingsIcons.Line
    ConnectionPath.Kind.PHONE -> Icons.Filled.Phone
    ConnectionPath.Kind.NO_LINE, ConnectionPath.Kind.NOTHING -> Icons.Filled.Close
}

/** the settings page a step's settings are on */
private fun pageOf(p: ConnectionPath.Page): SettingsPage = when (p) {
    ConnectionPath.Page.TERMINAL -> SettingsPage.TERMINAL
    ConnectionPath.Page.INTERNET -> SettingsPage.INTERNET
    ConnectionPath.Page.BRIDGES -> SettingsPage.BRIDGES
    ConnectionPath.Page.MODEM -> SettingsPage.MODEM
    ConnectionPath.Page.LINE -> SettingsPage.LINE
}

/** the settings' line as the path's */
private fun lineOf(s: ModemSettings): ConnectionPath.Line = when (s.line) {
    ModemSettings.LINE_WIFI -> ConnectionPath.Line.WIFI
    ModemSettings.LINE_BLUETOOTH -> ConnectionPath.Line.BLUETOOTH
    ModemSettings.LINE_CELL -> ConnectionPath.Line.CELL
    ModemSettings.LINE_NONE -> ConnectionPath.Line.NONE
    ModemSettings.LINE_USB -> ConnectionPath.Line.USB
    ModemSettings.LINE_BT_MODEM -> ConnectionPath.Line.BT_MODEM
    ModemSettings.LINE_SERIAL_MODEM -> ConnectionPath.Line.SERIAL_MODEM
    else -> ConnectionPath.Line.SIP
}

/** the bridges on, a word each */
private fun bridgeNames(b: BridgeSettings): List<String> = buildList {
    if (b.tcp) add("TCP")
    if (b.bluetooth) add("Bluetooth")
    if (b.usb || b.usbGadget) add("USB serial")
}

/** the call's rate, online: "48000/26400 bit/s" (down/up), or one rate when both are the same */
private fun rateOf(st: NativeModem.Status): String = when {
    st.link != NativeModem.Link.ONLINE || (st.rxRate <= 0 && st.txRate <= 0) -> ""
    st.rxRate == st.txRate -> "${st.rxRate} bit/s"
    else -> "${st.rxRate}/${st.txRate} bit/s"
}
