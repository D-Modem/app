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

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DModemTheme {
                var tab by rememberSaveable { mutableIntStateOf(TAB_MODEM) }
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                val full = fullscreen && tab == TAB_TERMINAL

                LaunchedEffect(full) { setSystemBars(!full) }
                BackHandler(enabled = full) { fullscreen = false }

                Scaffold(
                    bottomBar = {
                        if (!full) {
                            NavigationBar {
                                Item(tab == TAB_MODEM, "Home", { Icon(Icons.Filled.Home, null) }) { tab = TAB_MODEM }
                                Item(tab == TAB_TERMINAL, "Terminal", { Text(">_") }) { tab = TAB_TERMINAL }
                                Item(tab == TAB_DIRECTORY, "Dial", { Icon(Icons.Filled.Call, null) }) { tab = TAB_DIRECTORY }
                                Item(tab == TAB_SETTINGS, "Settings", { Icon(Icons.Filled.Settings, null) }) { tab = TAB_SETTINGS }
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
                        TAB_SETTINGS -> SettingsScreen(m)
                        else -> LogScreen(m)
                    }
                }
            }
        }
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
