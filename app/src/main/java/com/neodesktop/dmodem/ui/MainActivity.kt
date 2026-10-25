// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.SystemBarStyle
import android.app.UiModeManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.neodesktop.dmodem.settings.AppearanceStore
import com.neodesktop.dmodem.settings.DarkMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    /** a tab to go to, from outside the content (a call answered from its notification) */
    private val goTo = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (a fresh start: Settings at its menu)
        if (savedInstanceState == null) SettingsNav.open(null)
        enableEdgeToEdge()
        // notifications, and the LAN (a SIP server, an SSH host, a bridge's client there: Android 17 asks)
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
        // the internet host's service, if it was on (the app in the foreground now: it may start)
        lifecycleScope.launch {
            val b = com.neodesktop.dmodem.settings.BridgeSettingsStore(applicationContext).current()
            if (b.internet && b.anyOn) runCatching { com.neodesktop.dmodem.bridge.HostService.update(applicationContext) }
        }
        val appearanceStore = AppearanceStore(applicationContext)
        handleCall(intent)
        // over the lock screen while a call rings (its full-screen notification), not after
        lifecycleScope.launch {
            com.neodesktop.dmodem.modem.ModemState.incoming.state.collect { c ->
                if (Build.VERSION.SDK_INT >= 27 && c == null) {
                    setShowWhenLocked(false)
                    setTurnScreenOn(false)
                }
            }
        }
        setContent {
            val appearance by appearanceStore.appearance.collectAsStateWithLifecycle(initialValue = null)
            val a = appearance ?: return@setContent
            val dark = isDark(a)
            LaunchedEffect(dark) {
                // the bars' icons for the app's background, not the system's mode
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            LaunchedEffect(a.dark) { applyNightMode(a.dark) }
            DModemTheme(a) {
                var tab by rememberSaveable { mutableIntStateOf(TAB_MODEM) }
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                val full = fullscreen && tab == TAB_TERMINAL

                LaunchedEffect(full) { setSystemBars(!full) }
                LaunchedEffect(Unit) {
                    goTo.collect { t ->
                        if (t != null) {
                            tab = t
                            goTo.value = null
                        }
                    }
                }
                // a call ringing in: the popup, over any tab
                val call by com.neodesktop.dmodem.modem.ModemState.incoming.state.collectAsStateWithLifecycle()
                call?.takeIf { !it.ignored }?.let { c ->
                    IncomingCallDialog(
                        c,
                        caller = callerOfLine(),
                        onAnswer = {
                            com.neodesktop.dmodem.modem.ModemState.answer()
                            tab = TAB_TERMINAL
                        },
                        onFax = { answerAsFax() },
                        onIgnore = { com.neodesktop.dmodem.modem.ModemState.incoming.ignore() },
                        offerFax = !com.neodesktop.dmodem.modem.ModemState.adaptiveAnswer,
                    )
                }
                // back: Home from another tab; on Home the app to the background, as Home's button would
                // (the modem going on), not closed. (Screens' own back, and full screen's below, come first.)
                BackHandler { if (tab != TAB_MODEM) tab = TAB_MODEM else moveTaskToBack(true) }
                BackHandler(enabled = full) { fullscreen = false }

                PromptHost()
                Scaffold(
                    bottomBar = {
                        if (!full) {
                            NavigationBar {
                                Item(tab == TAB_MODEM, "Home", { Icon(Icons.Filled.Home, null) }) { tab = TAB_MODEM }
                                Item(tab == TAB_TERMINAL, "Terminal", { Text(">_") }) { tab = TAB_TERMINAL }
                                Item(tab == TAB_DIRECTORY, "Dial", { Icon(Icons.Filled.Call, null) }) { tab = TAB_DIRECTORY }
                                Item(tab == TAB_SETTINGS, "Settings", { Icon(Icons.Filled.Settings, null) }) {
                                    // (Settings again from within it: its menu)
                                    if (tab == TAB_SETTINGS) SettingsNav.open(null)
                                    tab = TAB_SETTINGS
                                }
                                Item(tab == TAB_LOG, "Log", { Icon(Icons.AutoMirrored.Filled.List, null) }) { tab = TAB_LOG }
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding).consumeWindowInsets(padding)
                    when (tab) {
                        TAB_MODEM -> DashboardScreen(
                            m,
                            onOpenTerminal = { tab = TAB_TERMINAL },
                            onOpenDirectory = { tab = TAB_DIRECTORY },
                            onOpenSettings = { tab = TAB_SETTINGS },
                        )
                        TAB_TERMINAL -> TerminalScreen(m, fullscreen = full, onFullscreen = { fullscreen = it })
                        TAB_DIRECTORY -> DirectoryScreen(m, onDialed = { tab = TAB_TERMINAL })
                        TAB_SETTINGS -> SettingsScreen(m, onOpenTerminal = { tab = TAB_TERMINAL })
                        else -> LogScreen(m)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleCall(intent)
    }

    /** the incoming call's notification: opened (the popup), answered, or answered as a fax */
    private fun handleCall(i: android.content.Intent?) {
        when (i?.action) {
            ACTION_INCOMING, ACTION_ANSWER, ACTION_ANSWER_FAX -> {
                // (over the lock screen, screen on: as a phone's incoming call)
                if (Build.VERSION.SDK_INT >= 27 && com.neodesktop.dmodem.modem.ModemState.incoming.state.value != null) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }
            }
            else -> return
        }
        when (i.action) {
            ACTION_ANSWER -> {
                com.neodesktop.dmodem.modem.ModemState.answer()
                goTo.value = TAB_TERMINAL
            }
            ACTION_ANSWER_FAX -> answerAsFax()
        }
        // (once: a rotation's re-creation doesn't answer again)
        i.action = null
    }

    private fun answerAsFax() {
        com.neodesktop.dmodem.modem.ModemState.incoming.end()
        val why = com.neodesktop.dmodem.fax.Fax.answer(this)
        if (why != null) android.widget.Toast.makeText(this, "Fax: $why", android.widget.Toast.LENGTH_LONG).show()
        goTo.value = TAB_MODEM
    }

    /** the line's word for a caller it doesn't name */
    private fun callerOfLine(): String = when (com.neodesktop.dmodem.modem.ModemState.line) {
        com.neodesktop.dmodem.settings.ModemSettings.LINE_WIFI, com.neodesktop.dmodem.settings.ModemSettings.LINE_BLUETOOTH -> "The other phone"
        com.neodesktop.dmodem.settings.ModemSettings.LINE_CELL -> "A mobile call"
        com.neodesktop.dmodem.settings.ModemSettings.LINE_USB -> "A call on the phone line"
        else -> "A SIP call"
    }

    /** the system's own UI for the app (its splash screen, its dialogs) in the app's mode, from Android 12 */
    private fun applyNightMode(mode: DarkMode) {
        if (Build.VERSION.SDK_INT < 31) return
        val ui = getSystemService(UiModeManager::class.java) ?: return
        val want = when (mode) {
            DarkMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            DarkMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            DarkMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { ui.setApplicationNightMode(want) }
    }

    private fun setSystemBars(show: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (show) {
            c.show(WindowInsetsCompat.Type.systemBars())
        } else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** the incoming call's notification: opened, answered, answered as a fax */
        const val ACTION_INCOMING = "com.neodesktop.dmodem.INCOMING_CALL"
        const val ACTION_ANSWER = "com.neodesktop.dmodem.ANSWER"
        const val ACTION_ANSWER_FAX = "com.neodesktop.dmodem.ANSWER_FAX"

        private const val TAB_MODEM = 0
        private const val TAB_TERMINAL = 1
        private const val TAB_DIRECTORY = 2
        private const val TAB_SETTINGS = 3
        private const val TAB_LOG = 4
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    selected: Boolean,
    label: String,
    icon: @androidx.compose.runtime.Composable () -> Unit,
    onClick: () -> Unit,
) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = icon, label = { Text(label) })
}
