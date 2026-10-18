// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import SwiftUI

/// What the modem is doing and the one thing to do about it (start, hang
/// up, stop); its rates online and what the CONNECT was; an AT command line
/// and the modem's last words.
struct HomeView: View {
    @ObservedObject var model: AppModel
    @State private var at = ""

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Circle().fill(dotColor).frame(width: 12, height: 12)
                        Text(title).font(.title2.weight(.semibold))
                        Spacer()
                        if let since = model.onlineSince {
                            TimelineView(.periodic(from: since, by: 1)) { ctx in
                                Text(elapsed(since, ctx.date)).font(.title3.monospacedDigit())
                            }
                        }
                    }
                    if model.status.link == .online {
                        HStack(spacing: 28) {
                            rate("↓ down", model.status.rxRate)
                            rate("↑ up", model.status.txRate)
                        }
                        if !model.connectLabel.isEmpty {
                            Text(model.connectLabel).font(.body).foregroundStyle(.secondary)
                        }
                    } else {
                        Text(subtitle).foregroundStyle(.secondary)
                    }
                    if let e = model.error {
                        Text(e).foregroundStyle(.red).font(.callout)
                    }
                    actions
                }
                .padding(.vertical, 6)
            }
            Section("Line") {
                if model.settings.line == .wifi {
                    LabeledContent("Another phone", value: model.settings.peerAddress.isEmpty ? "none chosen" : model.settings.peerAddress)
                    Text(model.peerState ?? "Starts with the modem").foregroundStyle(.secondary)
                    if let e = model.peerError {
                        Text(e).foregroundStyle(.red).font(.callout)
                    }
                } else {
                    LabeledContent("SIP account", value: model.settings.complete ? "\(model.settings.user)@\(model.settings.domain)" : "not set up")
                    LabeledContent("Fax", value: model.settings.t38 ? "audio or T.38" : "audio")
                }
                LabeledContent("Incoming calls", value: model.settings.listen ? "taken" : "off")
            }
            if model.run == .running {
                Section("AT command") {
                    HStack {
                        TextField("ATI3", text: $at)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .font(.body.monospaced())
                            .onSubmit(sendAt)
                        Button("Send", action: sendAt).disabled(at.isEmpty)
                    }
                    Text(lastLines).font(.caption.monospaced()).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .navigationTitle("D-Modem")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink { LogView(model: model) } label: { Image(systemName: "list.bullet.rectangle") }
                    .accessibilityLabel("Log")
            }
        }
    }

    @ViewBuilder private var actions: some View {
        HStack {
            switch model.run {
            case .stopped:
                Button("Start the modem") { model.start() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.settings.complete)
            case .running:
                if model.status.offHook {
                    Button("Hang up") { model.hangUp() }.buttonStyle(.borderedProminent)
                }
                Button("Stop") { model.stop() }.buttonStyle(.bordered)
            }
        }
    }

    private var title: String {
        switch (model.run, model.status.link) {
        case (.stopped, _): return "Modem off"
        case (_, .online): return "Online"
        case (_, .connecting): return "Connecting…"
        case (_, .hangingUp): return "Hanging up…"
        default: return model.status.offHook ? "Off hook" : "Ready"
        }
    }

    private var subtitle: String {
        switch (model.run, model.status.link) {
        case (.stopped, _): return model.settings.complete ? "Ready to start" : "Set up a SIP account to start"
        case (_, .connecting): return "Training the line"
        default: return model.status.offHook ? "Dialling" : "On hook, waiting for a dial (the Terminal: ATD)"
        }
    }

    private var dotColor: Color {
        if model.run == .stopped { return .gray }
        // (online, or ready on hook: green; anything on its way: orange)
        if model.status.link == .online || (model.status.link == .idle && !model.status.offHook) { return .green }
        return .orange
    }

    private var lastLines: String {
        model.console.split(whereSeparator: \.isNewline).suffix(6).joined(separator: "\n")
    }

    private func rate(_ label: String, _ bps: Int) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption)
            Text(bps >= 1000 ? String(format: "%.1f kbit/s", Double(bps) / 1000) : "\(bps) bit/s")
                .font(.title.weight(.bold))
        }
    }

    private func elapsed(_ since: Date, _ now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince(since)))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    private func sendAt() {
        let c = at.trimmingCharacters(in: .whitespaces)
        guard !c.isEmpty else { return }
        model.command(c.uppercased().hasPrefix("AT") ? c : "AT" + c)
        at = ""
    }
}
