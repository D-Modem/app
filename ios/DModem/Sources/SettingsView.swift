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
            Section("Calls") {
                Toggle("Take incoming calls", isOn: $model.settings.listen)
                Toggle("Fax over T.38", isOn: $model.settings.t38)
                Toggle("Speaker (the dialling and the handshake)", isOn: $model.settings.speaker)
                Stepper("Local SIP port: \(model.settings.sipPort)", value: $model.settings.sipPort, in: 1024...65535)
            }
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
        .navigationTitle("Settings")
    }
}
