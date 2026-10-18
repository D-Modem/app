// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import SwiftUI

/// D-Modem for iOS: the modem on a SIP account, its terminal, its log.
@main
struct DModemApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { HomeView(model: model) }
                    .tabItem { Label("Home", systemImage: "house") }
                NavigationStack { TerminalScreen(model: model.terminal) }
                    .tabItem { Label("Terminal", systemImage: "terminal") }
                NavigationStack { LogView(model: model) }
                    .tabItem { Label("Log", systemImage: "list.bullet.rectangle") }
                NavigationStack { SettingsView(model: model) }
                    .tabItem { Label("Settings", systemImage: "gear") }
            }
        }
    }
}
