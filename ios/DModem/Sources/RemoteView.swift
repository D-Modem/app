// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import SwiftUI

/// The Remote screen: SSH, Telnet or rlogin to a server; its terminal
/// once connected (the same terminal as the modem's, a screen of its own).
struct RemoteView: View {
    @ObservedObject var model: RemoteModel
    @State private var password = ""

    var body: some View {
        Group {
            if model.running {
                TerminalScreen(model: model.terminal)
                    .safeAreaInset(edge: .top) {
                        Text(model.status)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .background(Color(.secondarySystemBackground))
                    }
            } else {
                form
            }
        }
        .navigationTitle("Remote")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if model.running {
                    Button("Disconnect", role: .destructive) { model.disconnect() }
                } else {
                    Button("Connect") { model.connect() }
                        .disabled(!model.complete)
                }
            }
        }
        .alert(title, isPresented: Binding(get: { model.question != nil }, set: { if !$0 { model.answer(nil) } }), presenting: model.question) { q in
            switch q {
            case .password:
                SecureField("Password", text: $password)
                Button("Log In") {
                    let p = password
                    password = ""
                    model.answer(p)
                }
                Button("Cancel", role: .cancel) {
                    password = ""
                    model.answer(nil)
                }
            case .newHostKey:
                Button("Trust") { model.answer("yes") }
                Button("Cancel", role: .cancel) { model.answer(nil) }
            }
        } message: { q in
            switch q {
            case .password(let host, let reason):
                Text(reason.isEmpty ? "The password for \(host)" : "\(reason). The password for \(host)")
            case .newHostKey(let host, let fp):
                Text("This is the first connection to \(host). Its key is \(fp). Trust it and keep it?")
            }
        }
    }

    private var title: String {
        switch model.question {
        case .password: return "Log in"
        case .newHostKey: return "New host"
        case nil: return ""
        }
    }

    private var form: some View {
        Form {
            Section {
                Picker("Connect by", selection: $model.proto) {
                    ForEach(RemoteModel.Proto.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                TextField("Server", text: $model.host)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                TextField("Port (\(model.proto.defaultPort))", text: $model.port)
                    .keyboardType(.numberPad)
                TextField(model.proto == .telnet ? "User (optional)" : "User", text: $model.user)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } footer: {
                switch model.proto {
                case .ssh:
                    Text("Logs in with a password. The server's key is kept the first time you trust it.")
                case .telnet:
                    Text("For BBSes and older hosts. Nothing is encrypted.")
                case .rlogin:
                    Text("Sends the user name to log in as. Nothing is encrypted.")
                }
            }
            Section {
                Button("Connect") { model.connect() }
                    .disabled(!model.complete)
                if model.proto == .ssh {
                    Button("Forget this server's key", role: .destructive) { model.forgetHostKey() }
                }
            }
            if model.status != "Not connected" {
                Section { Text(model.status).foregroundStyle(.secondary) }
            }
        }
    }
}
