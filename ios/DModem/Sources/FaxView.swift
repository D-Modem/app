// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemKit
import DModemShared
import QuickLook
import SwiftUI
import UniformTypeIdentifiers

/// Faxes: a document (PDF, image, text) sent to a number; the ringing
/// call answered as a fax, by hand or each one; the call as it goes; the
/// faxes received (PDFs in Documents/Faxes), opened or shared.
struct FaxView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var jobs: JobsModel
    @State private var number = ""
    @State private var documents: [URL] = []
    @State private var picking = false
    @State private var error: String?
    @State private var received: [URL] = []
    @State private var preview: URL?

    var body: some View {
        Form {
            if jobs.preparing || jobs.fax != nil || jobs.faxError != nil { callSection }
            Section {
                TextField("Fax number", text: $number).keyboardType(.phonePad)
                ForEach(documents, id: \.self) { u in
                    Label(u.lastPathComponent, systemImage: "doc")
                }
                .onDelete { documents.remove(atOffsets: $0) }
                Button(documents.isEmpty ? "Choose a document" : "Add a document") { picking = true }
                Button("Send the fax") {
                    error = jobs.sendFax(number: number, documents: documents)
                }
                .disabled(number.isEmpty || documents.isEmpty || jobs.running || model.run != .running)
            } header: {
                Text("Send")
            } footer: {
                Text("PDFs, images and text, at the fax's width: photographs as dots, the rest black and white.")
            }
            Section {
                Button("Answer the ringing call as a fax") { error = model.answerFax() }
                    .disabled(jobs.running || model.run != .running)
                Toggle("Answer every call as a fax", isOn: $model.settings.faxAutoAnswer)
            } header: {
                Text("Receive")
            }
            if let e = error {
                Section { Text(e).foregroundStyle(.red) }
            }
            Section("Received") {
                if received.isEmpty {
                    Text("None yet").foregroundStyle(.secondary)
                }
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
            Section("Fax settings") {
                TextField("Our number (sent to the other machine)", text: $model.settings.faxIdent).keyboardType(.phonePad)
                Toggle("Error correction (ECM)", isOn: $model.settings.faxEcm)
                Toggle("Fine resolution", isOn: $model.settings.faxFine)
                Picker("Fastest rate", selection: $model.settings.faxMaxBps) {
                    ForEach([14400, 9600, 4800, 2400], id: \.self) { Text("\($0) bit/s").tag($0) }
                }
            }
        }
        .navigationTitle("Fax")
        .fileImporter(isPresented: $picking, allowedContentTypes: [.pdf, .image, .plainText], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { documents += urls.compactMap(copied) }
        }
        .quickLookPreview($preview)
        .onAppear(perform: reload)
        .onChange(of: jobs.fax?.running) { _ in reload() }
    }

    @ViewBuilder private var callSection: some View {
        Section {
            if jobs.preparing {
                HStack { ProgressView(); Text("Preparing the document") }
            }
            if let e = jobs.faxError {
                Text(e).foregroundStyle(.red)
                Button("Dismiss") { jobs.dismissFax() }
            }
            if let f = jobs.fax {
                let p = f.progress
                Text(f.running ? (p.phase.isEmpty ? "Starting" : p.phase) : (f.result == "" ? (f.sending ? "Sent" : "Received") : "Failed"))
                    .font(.headline)
                if p.total > 0 || p.pages > 0 {
                    Text(p.total > 0 ? "Page \(min(p.pages + (f.running ? 1 : 0), p.total)) of \(p.total)" : "\(p.pages) page\(p.pages == 1 ? "" : "s")")
                }
                if p.bps > 0 {
                    Text("\(p.bps) bit/s\(p.fine ? ", fine" : "")\(p.ecm ? ", ECM" : "")").foregroundStyle(.secondary)
                }
                if !p.remote.isEmpty { LabeledContent("The other machine", value: p.remote) }
                if let r = f.result, !r.isEmpty { Text(r).foregroundStyle(.red) }
                if f.running {
                    Button("Hang up", role: .destructive) { jobs.cancelFax() }
                } else {
                    if !f.file.isEmpty {
                        Button("Open \(f.file)") { preview = AppFolders.faxes.appendingPathComponent(f.file) }
                    }
                    Button("Dismiss") { jobs.dismissFax() }
                }
            }
        } header: {
            Text(jobs.fax?.sending == false ? "Receiving" : "Sending")
        }
    }

    /// the received faxes listed again from the faxes folder
    private func reload() { received = AppFolders.files(AppFolders.faxes) }

    /// a picked file copied where the app may read it later (a picked one may only be read while open to us)
    private func copied(_ u: URL) -> URL? {
        let scoped = u.startAccessingSecurityScopedResource()
        defer { if scoped { u.stopAccessingSecurityScopedResource() } }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("fax-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(u.lastPathComponent)
        return (try? FileManager.default.copyItem(at: u, to: dest)) != nil ? dest : nil
    }
}
