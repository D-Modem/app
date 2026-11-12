// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import SwiftUI
import UIKit

/// The Remote screen: SSH, Telnet or rlogin to a server; its terminal
/// once connected (the same terminal as the modem's, a screen of its own).
/// For SSH, a private key to log in with: one chosen from Files (PuTTY's
/// .ppk imported) or made here.
struct RemoteView: View {
    @ObservedObject var model: RemoteModel
    @State private var password = ""
    @State private var choosingKey = false

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
            case .passphrase:
                SecureField("Passphrase", text: $password)
                Button("OK") {
                    let p = password
                    password = ""
                    model.answer(p)
                }
                Button("Cancel", role: .cancel) {
                    password = ""
                    model.answer(nil)
                }
            }
        } message: { q in
            switch q {
            case .password(let host, let reason):
                Text(reason.isEmpty ? "The password for \(host)" : "\(reason). The password for \(host)")
            case .newHostKey(let host, let fp):
                Text("This is the first connection to \(host). Its key is \(fp). Trust it and keep it?")
            case .passphrase(let name, let why):
                Text(why.isEmpty ? "The passphrase of \(name)" : "\(why). The passphrase of \(name)")
            }
        }
    }

    private var title: String {
        switch model.question {
        case .password: return "Log in"
        case .newHostKey: return "New host"
        case .passphrase: return "Private key"
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
                    Text(model.useKey && !model.keyName.isEmpty
                        ? "Logs in with your private key, else a password. The server's key is kept the first time you trust it."
                        : "Logs in with a password. The server's key is kept the first time you trust it.")
                case .telnet:
                    Text("For BBSes and older hosts. Nothing is encrypted.")
                case .rlogin:
                    Text("Sends the user name to log in as. Nothing is encrypted.")
                }
            }
            if model.proto == .ssh { keySection }
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
        .fileImporter(isPresented: $choosingKey, allowedContentTypes: [.item]) { r in
            if case .success(let url) = r { model.chooseKey(url) }
        }
    }

    /// the private key to log in with: the one kept, or one to choose or make
    private var keySection: some View {
        Section {
            if model.keyName.isEmpty {
                Button("Choose a Key File…") { choosingKey = true }
                Button("Make a New Key") { model.makeKey() }
            } else {
                Toggle("Log in with my key", isOn: $model.useKey)
                LabeledContent("Key", value: model.keyName)
                Button("Copy Public Key") { UIPasteboard.general.string = model.keyPublic }
                Button("Choose Another Key File…") { choosingKey = true }
                Button("Remove This Key", role: .destructive) { model.forgetKey() }
            }
        } header: {
            Text("Private key")
        } footer: {
            Text(model.keyNote.isEmpty
                ? "OpenSSH's keys and PuTTY's .ppk keys can be chosen; a .ppk is imported in OpenSSH's form. The key stays in this app."
                : model.keyNote)
        }
    }
}
