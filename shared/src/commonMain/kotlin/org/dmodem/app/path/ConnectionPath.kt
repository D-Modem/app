// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.path

/**
 * The connection path: the picture at the top of Settings of how a call is
 * made, as PCOMM draws its session's link. Four steps, left to right:
 *
 *  1. what the user works in: the terminal (dial-up internet's PPP while
 *     it's up), and what else shares the modem (the bridges, dial-in);
 *  2. the modem: D-Modem's own, or for a modem of its own, what the terminal
 *     reaches it by (a USB serial adapter, Bluetooth);
 *  3. the line: SIP, another phone on Wi-Fi or Bluetooth, a USB modem's
 *     phone line, the phone's mobile calls, none; or the modem of its own;
 *  4. what is reached: the phone network, SIP addresses, the other phone.
 *
 * Idle, each step is as it's set up (ready, or needing something set);
 * starting, calling and online, as it is now. Each platform draws the
 * steps; the words are here so that Android and iOS say the same.
 */
object ConnectionPath {

    /** the modem's line, as the settings have it (Android's ModemSettings.line, iOS's ModemSettings.Line) */
    enum class Line { SIP, WIFI, BLUETOOTH, CELL, NONE, USB, BT_MODEM, SERIAL_MODEM }

    /** the modem's run and its call, now */
    enum class Run { STOPPED, STARTING, RUNNING, STOPPING }
    enum class Call { IDLE, CONNECTING, ONLINE, HANGING_UP }

    /**
     * a step's state: [READY] set up (idle); [SETUP] something to set first;
     * [OFF] not in use; [WORKING] starting or connecting; [ACTIVE] in the call
     */
    enum class State { READY, SETUP, OFF, WORKING, ACTIVE }

    /** what a step is, for its icon */
    enum class Kind {
        TERMINAL, PPP, SOFTMODEM, SERIAL, BLUETOOTH, MODEM, SIP, WIFI, CELL, USB, NO_LINE,
        PHONE_NETWORK, SIP_ADDRESS, PHONE, PHONE_LINE, NOTHING,
    }

    /** the settings page a step's settings are on */
    enum class Page { TERMINAL, INTERNET, BRIDGES, MODEM, LINE }

    /**
     * a step: its icon, its name, a line of what it's set to, its state, where it's set; [role] what
     * part it plays, the label above it (PCOMM's "Interface", "Attachment", "Type of Host")
     */
    data class Step(val kind: Kind, val title: String, val detail: String, val state: State, val page: Page, val role: String = "")

    /** the path: its steps, and the whole in a line */
    data class Path(val steps: List<Step>, val headline: String) {
        /** the link between step [i] and the next: the lesser of the two (OFF least, then SETUP) */
        fun link(i: Int): State = lesser(steps[i].state, steps[i + 1].state)

        /** the path for a screen reader: the headline, then each step */
        val spoken: String get() = headline + ". " + steps.joinToString(", then ") { "${it.title}: ${it.detail}" }
    }

    /** the settings the path is drawn from */
    data class Config(
        val line: Line,
        /** SIP: no account; the account's user and server; taking calls (registering) */
        val sipAnonymous: Boolean = false,
        val sipUser: String = "",
        val sipDomain: String = "",
        val listen: Boolean = false,
        /** another phone's (Wi-Fi, Bluetooth): its name, its address */
        val peerName: String = "",
        val peerAddress: String = "",
        /** a Bluetooth modem's: its name, its address */
        val btModemName: String = "",
        val btModemAddress: String = "",
        /** a modem on a USB serial adapter's speed, and RTS/CTS */
        val serialBaud: Int = 115200,
        val serialRtsCts: Boolean = true,
        /** the USB device plugged in for the line (a USB modem, a serial adapter): its name; "" none; null not known */
        val usbDevice: String? = null,
        /** mobile calls' modulation (+MS) */
        val cellSpeed: String = "",
        /** dial-up internet's ISP is set up; dial-in for callers is on */
        val isp: Boolean = false,
        val dialIn: Boolean = false,
        /** the bridges on, in a word each ("TCP", "Bluetooth", "USB serial") */
        val bridges: List<String> = emptyList(),
    )

    /** how it is now: the modem's run, its call, the call's rate, dial-up internet up */
    data class Live(
        val run: Run = Run.STOPPED,
        val call: Call = Call.IDLE,
        val rate: String = "",
        val internet: Boolean = false,
    ) {
        val idle: Boolean get() = call == Call.IDLE && (run == Run.STOPPED || run == Run.RUNNING) && !internet
    }

    /** the path for [c], now as [live] is */
    fun of(c: Config, live: Live = Live()): Path {
        val steps = mutableListOf(host(c, live))
        steps += when (c.line) {
            Line.SERIAL_MODEM, Line.BT_MODEM -> external(c)
            else -> softmodem(c, live)
        }
        // (a call: each step in use lights; idle: as set up)
        val now = when {
            live.call == Call.ONLINE || live.internet -> State.ACTIVE
            live.call == Call.CONNECTING || live.call == Call.HANGING_UP -> State.WORKING
            else -> null
        }
        val roles = if (c.line == Line.SERIAL_MODEM || c.line == Line.BT_MODEM) EXTERNAL_ROLES else ROLES
        val shown = steps.mapIndexed { i, it ->
            val st = if (now == null || it.state == State.OFF || it.state == State.SETUP) it.state else now
            it.copy(state = st, role = roles[i])
        }
        return Path(shown, headline(shown, c, live))
    }

    /** a dial-up internet session is shown as PPP; otherwise the terminal, and what shares the modem */
    private fun host(c: Config, live: Live): Step {
        if (live.internet) return Step(Kind.PPP, "Internet", "PPP to the ISP", State.READY, Page.INTERNET)
        val also = buildList {
            if (c.isp) add("dial-up internet")
            if (c.dialIn) add("dial-in")
            addAll(c.bridges.map { "$it bridge" })
        }
        val detail = if (also.isEmpty()) "AT commands and calls" else "Also " + also.joinToString(", ")
        return Step(Kind.TERMINAL, "Terminal", detail, State.READY, Page.TERMINAL)
    }

    /** D-Modem's own modem, its line, and what it reaches */
    private fun softmodem(c: Config, live: Live): List<Step> {
        val modem = when (live.run) {
            Run.STARTING -> Step(Kind.SOFTMODEM, "D-Modem", "Starting", State.WORKING, Page.MODEM)
            Run.STOPPING -> Step(Kind.SOFTMODEM, "D-Modem", "Stopping", State.WORKING, Page.MODEM)
            Run.RUNNING -> Step(
                Kind.SOFTMODEM, "D-Modem",
                if (live.call == Call.ONLINE && live.rate.isNotEmpty()) live.rate else "Running", State.READY, Page.MODEM,
            )
            Run.STOPPED -> Step(Kind.SOFTMODEM, "D-Modem", "V.92 softmodem", State.READY, Page.MODEM)
        }
        return listOf(modem) + when (c.line) {
            Line.SIP -> sip(c)
            Line.WIFI, Line.BLUETOOTH -> peer(c)
            Line.CELL -> listOf(
                Step(Kind.CELL, "Mobile call", "This phone's calls" + c.cellSpeed.let { if (it.isEmpty()) "" else ", $it" }, State.READY, Page.LINE),
                Step(Kind.PHONE_NETWORK, "Phone network", "Through the mobile network", State.READY, Page.LINE),
            )
            Line.USB -> {
                val dev = c.usbDevice
                listOf(
                    Step(
                        Kind.USB, "USB modem",
                        when { dev == null -> "Its phone line"; dev.isEmpty() -> "None plugged in"; else -> dev },
                        if (dev != null && dev.isEmpty()) State.SETUP else State.READY, Page.LINE,
                    ),
                    Step(Kind.PHONE_LINE, "Phone line", "The USB modem's", State.READY, Page.LINE),
                )
            }
            Line.NONE -> listOf(
                Step(Kind.NO_LINE, "No line", "Dials get NO DIALTONE", State.OFF, Page.LINE),
                Step(Kind.NOTHING, "Nothing", "The modem on its own", State.OFF, Page.LINE),
            )
            Line.SERIAL_MODEM, Line.BT_MODEM -> emptyList()
        }
    }

    /** SIP: an account (registered when taking calls) or none (anonymous) */
    private fun sip(c: Config): List<Step> = when {
        c.sipAnonymous -> listOf(
            Step(Kind.SIP, "SIP", "Anonymous: nothing registered" + if (c.listen) ", takes direct calls" else "", State.READY, Page.LINE),
            Step(Kind.SIP_ADDRESS, "SIP addresses", "Dialled directly (sip:user@host)", State.READY, Page.LINE),
        )
        c.sipUser.isBlank() || c.sipDomain.isBlank() -> listOf(
            Step(Kind.SIP, "SIP", "No account set up", State.SETUP, Page.LINE),
            Step(Kind.PHONE_NETWORK, "Phone network", "Through a SIP provider", State.SETUP, Page.LINE),
        )
        else -> listOf(
            Step(Kind.SIP, "SIP", (if (c.listen) "Registers as " else "Account ") + "${c.sipUser}@${c.sipDomain}", State.READY, Page.LINE),
            Step(Kind.PHONE_NETWORK, "Phone network", "Through ${c.sipDomain}", State.READY, Page.LINE),
        )
    }

    /** another phone with the app: chosen (to call), or only taking its calls */
    private fun peer(c: Config): List<Step> {
        val bt = c.line == Line.BLUETOOTH
        val who = c.peerName.ifBlank { c.peerAddress }
        val chosen = c.peerAddress.isNotBlank()
        val link = Step(
            if (bt) Kind.BLUETOOTH else Kind.WIFI, if (bt) "Bluetooth" else "Wi-Fi",
            when { chosen && bt -> "Paired"; chosen -> "Same network"; c.listen -> "Waiting for its call"; bt -> "Pair and choose a phone"; else -> "Choose a phone" },
            if (chosen || c.listen) State.READY else State.SETUP, Page.LINE,
        )
        val other = Step(
            Kind.PHONE, "Other phone",
            when { chosen -> who; c.listen -> "Any that calls"; else -> "Not chosen yet" },
            if (chosen || c.listen) State.READY else State.SETUP, Page.LINE,
        )
        return listOf(link, other)
    }

    /** a modem of its own: what the terminal reaches it by, the modem, its phone line */
    private fun external(c: Config): List<Step> {
        if (c.line == Line.BT_MODEM) {
            val chosen = c.btModemAddress.isNotBlank()
            return listOf(
                Step(Kind.BLUETOOTH, "Bluetooth", if (chosen) "Paired" else "Pair and choose a modem", if (chosen) State.READY else State.SETUP, Page.LINE),
                Step(Kind.MODEM, "Bluetooth modem", c.btModemName.ifBlank { c.btModemAddress.ifBlank { "Not chosen yet" } }, if (chosen) State.READY else State.SETUP, Page.LINE),
                Step(Kind.PHONE_LINE, "Phone line", "The modem's own", State.READY, Page.LINE),
            )
        }
        val dev = c.usbDevice
        val plugged = dev == null || dev.isNotEmpty()
        return listOf(
            Step(
                Kind.SERIAL, "USB serial",
                (if (dev.isNullOrEmpty()) "" else "$dev, ") + "${c.serialBaud} bit/s" + if (c.serialRtsCts) ", RTS/CTS" else "",
                if (plugged) State.READY else State.SETUP, Page.LINE,
            ),
            Step(Kind.MODEM, "Modem", if (plugged) "On the adapter" else "Plug in its adapter", if (plugged) State.READY else State.SETUP, Page.LINE),
            Step(Kind.PHONE_LINE, "Phone line", "The modem's own", State.READY, Page.LINE),
        )
    }

    /** the whole in a line: what needs setting, or what it's doing, or that it's ready */
    private fun headline(steps: List<Step>, c: Config, live: Live): String {
        val todo = steps.firstOrNull { it.state == State.SETUP }
        return when {
            live.internet -> "Online: dial-up internet" + if (live.rate.isNotEmpty()) " at ${live.rate}" else ""
            live.call == Call.ONLINE -> "Online" + if (live.rate.isNotEmpty()) " at ${live.rate}" else ""
            live.call == Call.CONNECTING -> "Connecting"
            live.call == Call.HANGING_UP -> "Hanging up"
            todo != null -> "Not ready: ${todo.title} (${todo.detail.replaceFirstChar { it.lowercase() }})"
            c.line == Line.NONE -> "No line: the modem answers AT commands only"
            live.run == Run.STARTING -> "Starting the modem"
            live.run == Run.RUNNING -> "Ready: the modem is running"
            else -> "Ready to dial"
        }
    }

    /** the steps' roles: D-Modem's own modem's path, a modem of its own's */
    private val ROLES = listOf("Program", "Modem", "Line", "Reaches")
    private val EXTERNAL_ROLES = listOf("Program", "Adapter", "Modem", "Line")

    /** the lesser of two states: OFF, then SETUP, then WORKING, READY, ACTIVE */
    fun lesser(a: State, b: State): State {
        fun rank(s: State) = when (s) {
            State.OFF -> 0; State.SETUP -> 1; State.WORKING -> 2; State.READY -> 3; State.ACTIVE -> 4
        }
        return if (rank(a) <= rank(b)) a else b
    }
}
