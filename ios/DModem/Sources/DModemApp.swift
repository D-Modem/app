// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import SwiftUI

/// D-Modem for iOS: the modem on a SIP account (or another phone), its
/// terminal and file transfers, faxes, dial-up internet.
@main
struct DModemApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { HomeView(model: model) }
                    .tabItem { Label("Home", systemImage: "house") }
                NavigationStack { TerminalTab(model: model, jobs: model.jobs) }
                    .tabItem { Label("Terminal", systemImage: "terminal") }
                NavigationStack { FaxView(model: model, jobs: model.jobs) }
                    .tabItem { Label("Fax", systemImage: "printer") }
                NavigationStack { InternetView(model: model, jobs: model.jobs) }
                    .tabItem { Label("Internet", systemImage: "globe") }
                NavigationStack { SettingsView(model: model) }
                    .tabItem { Label("Settings", systemImage: "gear") }
            }
        }
    }
}

/// the terminal, its file transfers a button away (and shown when the remote starts one)
private struct TerminalTab: View {
    @ObservedObject var model: AppModel
    @ObservedObject var jobs: JobsModel
    @State private var transfers = false

    var body: some View {
        TerminalScreen(model: model.terminal)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { transfers = true } label: { Image(systemName: "arrow.up.arrow.down.circle") }
                        .accessibilityLabel("File transfer")
                }
            }
            .sheet(isPresented: $transfers) { TransferView(jobs: jobs) }
            .onChange(of: jobs.transfer?.running) { r in if r == true { transfers = true } }
            .onChange(of: jobs.filesWanted) { w in if w { transfers = true } }
    }
}
