// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemShared
import SwiftUI

/// Dial-up internet: an ISP dialled on the modem, PPP up, the phone's
/// traffic through it (a VPN, the app's own: iOS asks to allow it the
/// first time); the link as it goes, its address, what's gone each way.
struct InternetView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var jobs: JobsModel
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                if let s = jobs.internet {
                    status(s)
                } else {
                    Text("Not connected").font(.headline)
                }
                if jobs.internet?.running == true {
                    Button("Disconnect", role: .destructive) { jobs.disconnectInternet() }
                } else {
                    Button("Connect") {
                        jobs.dismissInternet()
                        error = jobs.connectInternet()
                    }
                    .disabled(model.run != .running || model.settings.ispNumber.isEmpty || jobs.running)
                }
                if let e = error { Text(e).foregroundStyle(.red) }
            } footer: {
                Text("While it's up the phone's internet goes through the call, at the modem's speed. The first time, iOS asks to add D-Modem's VPN.")
            }
            Section {
                TextField("Number", text: $model.settings.ispNumber).keyboardType(.phonePad)
                TextField("User", text: $model.settings.ispUser)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Password", text: $model.settings.ispPassword)
                TextField("Modem commands first (AT…; between)", text: $model.settings.ispInit)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled().font(.body.monospaced())
            } header: {
                Text("The ISP")
            }
            Section {
                TextEditor(text: $model.settings.ispScript)
                    .font(.body.monospaced())
                    .frame(minHeight: 80)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: {
                Text("Login script (optional)")
            } footer: {
                Text("For an ISP that asks for a login before PPP, as chat does it: what's expected, then what's sent, e.g. ogin: \\U assword: \\P. Most need none: PPP logs in with the user and password.")
            }
        }
        .navigationTitle("Internet")
    }

    /// the dial-up's state: connected or its phase, and once connected its address, DNS, time up and bytes
    @ViewBuilder private func status(_ s: DialUpJob.State) -> some View {
        Text(s.running ? (s.connected ? "Connected" : s.phase) : "Disconnected").font(.headline)
        if !s.connect.isEmpty { Text(s.connect).font(.callout.monospaced()).foregroundStyle(.secondary) }
        if s.connected {
            LabeledContent("Address", value: s.address)
            if !s.dns.isEmpty { LabeledContent("DNS", value: s.dns.joined(separator: ", ")) }
            TimelineView(.periodic(from: Date(), by: 1)) { _ in
                LabeledContent("Up for", value: elapsed(Int(jobs.upMs / 1000)))
            }
            LabeledContent("In", value: ByteCountFormatter.string(fromByteCount: s.bytesIn, countStyle: .file))
            LabeledContent("Out", value: ByteCountFormatter.string(fromByteCount: s.bytesOut, countStyle: .file))
        }
        if let e = s.ended, e != "disconnected" { Text(e).foregroundStyle(.red) }
    }

    /// [s] seconds as m:ss, or h:mm:ss from an hour
    private func elapsed(_ s: Int) -> String {
        s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}
