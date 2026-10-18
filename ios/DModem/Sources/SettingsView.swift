// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import SwiftUI

/// The SIP account (or pasted as user:password@host), incoming calls, T.38,
/// the AT commands at start, the speaker, the log levels. Kept as they're
/// changed; they apply the next time the modem starts.
struct SettingsView: View {
    @ObservedObject var model: AppModel
    @State private var login = ""

    var body: some View {
        Form {
            Section {
                Picker("Line", selection: $model.settings.line) {
                    Text("SIP account").tag(ModemSettings.Line.sip)
                    Text("Another phone (Wi-Fi)").tag(ModemSettings.Line.wifi)
                }
            } footer: {
                Text(model.settings.line == .sip
                     ? "The modem calls through a SIP provider to the phone network."
                     : "The two phones' modems call each other as on a perfect phone line, no SIP account: each dials (ATD) or answers as usual. The other phone needs D-Modem (Android or iOS) on the same Wi-Fi, taking calls.")
            }
            if model.settings.line == .wifi {
                PeerSection(model: model)
            } else {
                sipSection
            }
            Section("Calls") {
                Toggle(model.settings.line == .sip ? "Take incoming calls" : "Take calls from the other phone", isOn: $model.settings.listen)
                if model.settings.line == .sip {
                    Toggle("Fax over T.38", isOn: $model.settings.t38)
                    Stepper("Local SIP port: \(model.settings.sipPort)", value: $model.settings.sipPort, in: 1024...65535)
                }
                Toggle("Speaker (the dialling and the handshake)", isOn: $model.settings.speaker)
            }
            modemSections
        }
        .navigationTitle("Settings")
    }

    @ViewBuilder private var sipSection: some View {
        Section {
            TextField("User", text: $model.settings.user)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            SecureField("Password", text: $model.settings.password)
            TextField("Server (host[:port])", text: $model.settings.domain)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
            TextField("Or paste user:password@host", text: $login)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .onSubmit {
                    if let l = ModemSettings.parseLogin(login) {
                        model.settings.user = l.user
                        model.settings.password = l.password
                        model.settings.domain = l.domain
                        login = ""
                    }
                }
        } header: {
            Text("SIP account")
        } footer: {
            Text("The provider must carry G.711 (PCMU/PCMA) to the phone network.")
        }
    }

    @ViewBuilder private var modemSections: some View {
        Section {
            TextField("AT commands at start", text: $model.settings.initCommands)
                .textInputAutocapitalization(.characters).autocorrectionDisabled().font(.body.monospaced())
        } header: {
            Text("Modem")
        } footer: {
            Text("ATX3 by default: a SIP line has no dial tone.")
        }
        Section("Log") {
            Stepper("Modem's level: \(model.settings.logLevel)", value: $model.settings.logLevel, in: 0...5)
            Stepper("SIP's level: \(model.settings.sipLogLevel)", value: $model.settings.sipLogLevel, in: 0...4)
        }
        if model.run == .running {
            Section {
                Text("Changes apply when the modem starts again.").foregroundStyle(.secondary)
            }
        }
    }
}

/// a phone found taking calls
private struct Found: Identifiable {
    let name: String
    let address: String
    var id: String { address }
}

/// the other phone: whom ATD calls (or found on the network), the port, the PIN
private struct PeerSection: View {
    @ObservedObject var model: AppModel
    @State private var finding = false
    @State private var found: [Found] = []
    @State private var finder: PeerFinder?

    var body: some View {
        Section {
            TextField("Phone to call (host[:port])", text: $model.settings.peerAddress)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
            Button(finding ? "Stop looking" : "Find phones on this network") { finding ? stop() : find() }
            if finding {
                if found.isEmpty {
                    Text("Looking… (phones taking calls on this Wi-Fi show here)").foregroundStyle(.secondary)
                }
                ForEach(found) { p in
                    Button {
                        model.settings.peerAddress = p.address
                        stop()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(p.name)
                            Text(p.address).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Stepper("Port calls come in on: \(model.settings.peerPort)", value: $model.settings.peerPort, in: 1024...65535)
            SecureField("PIN (optional)", text: $model.settings.peerPin)
        } header: {
            Text("The other phone")
        } footer: {
            Text("ATD with any number calls this phone; ATD192*168*1*20 calls that address instead. Set the same PIN on both phones: calls without it are turned down.")
        }
        .onDisappear(perform: stop)
    }

    private func find() {
        found = []
        let f = PeerFinder { name, address in
            if !found.contains(where: { $0.address == address }) { found.append(Found(name: name, address: address)) }
        }
        finder = f
        finding = true
        f.start()
    }

    private func stop() {
        finder?.stop()
        finder = nil
        finding = false
    }
}
