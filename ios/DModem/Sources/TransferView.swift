// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemShared
import QuickLook
import SwiftUI

/// File transfers on the call (the terminal's): files sent to the remote
/// or received from it by ZMODEM, YMODEM, XMODEM or Kermit, how far it has
/// got, the files received (Documents/Received). A remote's sz or kermit
/// -s starts one by itself; its rz asks for files here.
struct TransferView: View {
    @ObservedObject var jobs: JobsModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("transferProtocol") private var proto = "ZMODEM"
    @State private var picking = false
    @State private var error: String?
    @State private var received: [URL] = []
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            Form {
                if let t = jobs.transfer { progress(t) }
                if jobs.filesWanted {
                    Section { Text("The remote is waiting for files (rz): choose them to send.").foregroundStyle(.orange) }
                }
                Section {
                    Picker("Protocol", selection: $proto) {
                        ForEach(JobsModel.protocols, id: \.self) { Text(JobsModel.label($0)).tag($0) }
                    }
                    Button(JobsModel.batch(proto) ? "Send files" : "Send a file") { picking = true }
                        .disabled(jobs.running)
                    Button("Receive") { error = jobs.receiveFiles(proto) }
                        .disabled(jobs.running)
                } footer: {
                    Text("Start the remote's side first: \(remoteHint)")
                }
                if let e = error {
                    Section { Text(e).foregroundStyle(.red) }
                }
                Section("Received") {
                    if received.isEmpty { Text("None yet").foregroundStyle(.secondary) }
                    ForEach(received, id: \.self) { u in
                        HStack {
                            Button(u.lastPathComponent) { preview = u }
                            Spacer()
                            ShareLink(item: u) { Image(systemName: "square.and.arrow.up") }.buttonStyle(.borderless)
                        }
                    }
                    .onDelete { idx in
                        for i in idx { try? FileManager.default.removeItem(at: received[i]) }
                        reload()
                    }
                }
            }
            .navigationTitle("File transfer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.item], allowsMultipleSelection: JobsModel.batch(proto)) { r in
                if case .success(let urls) = r, !urls.isEmpty { error = jobs.sendFiles(proto, files: urls) }
            }
            .quickLookPreview($preview)
            .onAppear(perform: reload)
            .onChange(of: jobs.transfer?.running) { _ in reload() }
        }
    }

    /// what to type at the remote's shell
    private var remoteHint: String {
        switch proto {
        case "ZMODEM": return "sz file (it sends), rz (it receives; ZMODEM starts by itself either way)"
        case "YMODEM": return "sb file, rb"
        case "XMODEM": return "sx file, rx -c file"
        case "XMODEM_1K": return "sx -k file, rx -c file"
        default: return "kermit -i -s file, kermit -i -r"
        }
    }

    /// the transfer: which way or how it ended, the file and how far, files done, blocks resent, and Cancel or Dismiss
    @ViewBuilder private func progress(_ t: TransferState) -> some View {
        Section {
            let p = t.progress
            Text(t.running ? (jobs.transferSending ? "Sending" : "Receiving") : (t.result == "" ? "Done" : "Stopped"))
                .font(.headline)
            if !p.file.isEmpty {
                Text(p.file)
                if p.size > 0 {
                    ProgressView(value: Double(p.bytes), total: Double(p.size))
                    Text("\(bytes(p.bytes)) of \(bytes(p.size))").foregroundStyle(.secondary)
                } else {
                    Text(bytes(p.bytes)).foregroundStyle(.secondary)
                }
            }
            if p.files > 0 { Text("\(p.files) file\(p.files == 1 ? "" : "s") done") }
            if p.errors > 0 { Text("\(p.errors) block\(p.errors == 1 ? "" : "s") sent again").foregroundStyle(.secondary) }
            if let r = t.result, !r.isEmpty { Text(r).foregroundStyle(.red) }
            if t.running {
                Button("Cancel", role: .destructive) { jobs.cancelTransfer() }
            } else {
                Button("Dismiss") { jobs.dismissTransfer() }
            }
        }
    }

    /// [n] bytes as a file size (KB, MB …)
    private func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }

    /// the received files listed again from Documents/Received
    private func reload() { received = AppFolders.files(AppFolders.received) }
}
