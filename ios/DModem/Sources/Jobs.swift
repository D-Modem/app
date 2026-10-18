// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import DModemShared
import Foundation

/// What a shared Kotlin job calls back (DteOut, LogOut, Changed): its
/// octets to the modem's DTE (lent to it), its log to the modem's, its
/// changes to the main thread.
final class JobLink: NSObject, DteOut, LogOut, Changed, @unchecked Sendable {
    private let session: ModemSession
    private let onChange: @MainActor () -> Void

    init(session: ModemSession, onChange: @escaping @MainActor () -> Void) {
        self.session = session
        self.onChange = onChange
    }

    func write(latin1: String) -> Bool {
        guard let d = latin1.data(using: .isoLatin1) else { return false }
        return session.send(d)
    }

    func log(line: String) { session.engineLog(line) }

    func changed() {
        let f = onChange
        Task { @MainActor in f() }
    }
}

/// a line for the log, from Kotlin
final class LogLink: NSObject, LogOut, @unchecked Sendable {
    private let f: (String) -> Void
    init(_ f: @escaping (String) -> Void) { self.f = f }
    func log(line: String) { f(line) }
}

/// octets between Swift and Kotlin: Latin-1 text, a character an octet
enum Octets {
    static func latin1(_ d: Data) -> String { String(data: d, encoding: .isoLatin1) ?? "" }
    static func data(_ s: String) -> Data { s.data(using: .isoLatin1) ?? Data() }
}

/// where the app keeps files: in its Documents (the Files app shows them, "On My iPhone › D-Modem")
enum AppFolders {
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// received files (transfers)
    static var received: URL { folder("Received") }
    /// faxes received, as PDFs
    static var faxes: URL { folder("Faxes") }

    private static func folder(_ name: String) -> URL {
        let u = documents.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// the files in [dir], newest first
    static func files(_ dir: URL) -> [URL] {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isRegularFileKey]
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        return all.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            .sorted {
                let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return a > b
            }
    }
}
