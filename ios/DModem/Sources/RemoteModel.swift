// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import CryptoKit
import DModemShared
import DModemSSH
import Foundation
import Network

/// A login to a server by SSH, Telnet or rlogin, in a terminal of its own
/// (beside the modem's). SSH is DModemSSH's (libdmodemssh, on libssh), its host keys
/// kept here the first time they're accepted; Telnet and rlogin a TCP
/// connection (Network) with the shared Kotlin's protocol (LoginBridge).
/// An SSH login can be by a private key kept in the app: one chosen from
/// Files (OpenSSH's, PEM or PuTTY's .ppk, which is turned into OpenSSH's
/// form) or made here, its passphrase asked when it has one.
@MainActor
final class RemoteModel: ObservableObject {
    enum Proto: String, CaseIterable, Identifiable {
        case ssh = "SSH", telnet = "Telnet", rlogin = "rlogin"
        var id: String { rawValue }
        var defaultPort: Int {
            switch self {
            case .ssh: return 22
            case .telnet: return 23
            case .rlogin: return 513
            }
        }
    }

    /// a question for the user, answered with [answer]
    enum Question: Identifiable {
        case password(host: String, reason: String)
        case newHostKey(host: String, fingerprint: String)
        /// a private key's passphrase (why: "" the first time, else why
        /// the last was refused)
        case passphrase(name: String, why: String)
        var id: String {
            switch self {
            case .password(let h, let r): return "pw \(h) \(r)"
            case .newHostKey(let h, let f): return "key \(h) \(f)"
            case .passphrase(let n, let w): return "pp \(n) \(w)"
            }
        }
    }

    @Published var proto: Proto { didSet { save() } }
    @Published var host: String { didSet { save() } }
    /// empty: the protocol's
    @Published var port: String { didSet { save() } }
    @Published var user: String { didSet { save() } }
    @Published private(set) var running = false
    @Published private(set) var status = "Not connected"
    @Published var question: Question?
    /// SSH logs in with the kept private key (when there is one) before a password
    @Published var useKey: Bool { didSet { save() } }
    /// the kept key's name (the file it came from), "" for none
    @Published private(set) var keyName: String
    /// the kept key's public line, for the server's authorized_keys
    @Published private(set) var keyPublic: String
    /// what became of the last key chosen or made
    @Published var keyNote = ""

    let terminal = TerminalModel()

    private var answerQuestion: ((String?) -> Void)?
    private var ssh: SSHShell?
    private var connection: NWConnection?
    private var login: LoginBridge?
    private var cols = 80, rows = 24
    private let defaults = UserDefaults.standard

    /// the last server's settings, from the defaults
    init() {
        proto = Proto(rawValue: defaults.string(forKey: "remoteProtocol") ?? "") ?? .ssh
        host = defaults.string(forKey: "remoteHost") ?? ""
        port = defaults.string(forKey: "remotePort") ?? ""
        user = defaults.string(forKey: "remoteUser") ?? ""
        useKey = defaults.bool(forKey: "remoteUseKey")
        keyName = defaults.string(forKey: "remoteKeyName") ?? ""
        keyPublic = defaults.string(forKey: "remoteKeyPublic") ?? ""
        terminal.send = { [weak self] d in self?.send(d) }
        terminal.onResize = { [weak self] c, r in self?.resized(c, r) }
    }

    /// the settings kept in the defaults for next time
    private func save() {
        defaults.set(proto.rawValue, forKey: "remoteProtocol")
        defaults.set(host, forKey: "remoteHost")
        defaults.set(port, forKey: "remotePort")
        defaults.set(user, forKey: "remoteUser")
        defaults.set(useKey, forKey: "remoteUseKey")
    }

    var portNumber: Int { port.isEmpty ? proto.defaultPort : (Int(port) ?? 0) }

    /// enough to connect
    var complete: Bool {
        !host.trimmingCharacters(in: .whitespaces).isEmpty && (1...65535).contains(portNumber) &&
            (proto == .telnet || !user.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    // MARK: - connecting

    /// connect to the server on the screen, by its protocol (a key's
    /// passphrase asked first, when it has one)
    func connect() {
        guard !running, complete, question == nil else { return }
        if proto == .ssh, useKey, let key = keyText() {
            if !SSHShell.keyEncrypted(key) { return open(key: (key, nil)) }
            unlock(key, name: keyName) { [weak self] pass in
                if let pass { self?.open(key: (key, pass)) }
            }
            return
        }
        open(key: nil)
    }

    /// the connection started, by its protocol; SSH with that key first
    private func open(key: (key: String, passphrase: String?)?) {
        guard !running, complete else { return }
        let h = host.trimmingCharacters(in: .whitespaces)
        let u = user.trimmingCharacters(in: .whitespaces)
        let p = portNumber
        // (a Unix host's UTF-8 over SSH; a BBS's CP437 over Telnet and rlogin)
        terminal.bridge.setLook(themeId: "vga", boldBright: true, iceColors: false, charset: proto == .ssh ? "UTF8" : "CP437")
        let what = "Connecting to \(h) port \(p) by \(proto.rawValue)"
        status = what
        show("\u{1b}[0m[\(what)]\r\n")
        running = true
        cols = terminal.cols
        rows = terminal.rows
        if proto == .ssh {
            startSSH(host: h, port: p, user: u, key: key)
        } else {
            startSocket(host: h, port: p, user: u)
        }
    }

    /// the connection closed by the user
    func disconnect() { ended(nil) }

    /// an SSH login: its host key checked, its password asked
    private func startSSH(host: String, port: Int, user: String, key: (key: String, passphrase: String?)?) {
        let s = SSHShell(host: host, port: port, user: user, term: "xterm-256color", cols: cols, rows: rows)
        s.privateKey = key
        s.onData = { [weak self] d in Task { @MainActor in self?.terminal.feed(d) } }
        s.onClose = { [weak self] why in Task { @MainActor in self?.ended(why) } }
        s.verifyHostKey = { [weak self] key, ok in
            Task { @MainActor in
                guard let self else { return ok(false) }
                self.checkHostKey(host: host, port: port, key: key, ok)
            }
        }
        s.askPassword = { [weak self] reason, done in
            Task { @MainActor in
                guard let self else { return done(nil) }
                self.ask(.password(host: "\(user)@\(host)", reason: reason), done)
            }
        }
        ssh = s
        status = "Connected by SSH"
        s.connect()
    }

    /// the host's key: the one kept for it, or the user asked to accept a new one; a changed one refused
    private func checkHostKey(host: String, port: Int, key: String, _ ok: @escaping (Bool) -> Void) {
        let name = port == 22 ? host : "[\(host)]:\(port)"
        var known = defaults.dictionary(forKey: "sshKnownHosts") as? [String: String] ?? [:]
        if let k = known[name] {
            if k != key {
                show("\r\n[The host key of \(name) has changed. If that is expected, forget it in the Remote screen's menu.]\r\n")
            }
            ok(k == key)
            return
        }
        ask(.newHostKey(host: name, fingerprint: Self.fingerprint(key))) { [weak self] yes in
            if yes != nil {
                known[name] = key
                self?.defaults.set(known, forKey: "sshKnownHosts")
            }
            ok(yes != nil)
        }
    }

    /// forget the kept host key of the server on the screen now
    func forgetHostKey() {
        let p = portNumber
        let name = p == 22 ? host : "[\(host)]:\(p)"
        var known = defaults.dictionary(forKey: "sshKnownHosts") as? [String: String] ?? [:]
        known[name] = nil
        defaults.set(known, forKey: "sshKnownHosts")
    }

    /// OpenSSH's SHA256 fingerprint of a key in its "type base64" form
    static func fingerprint(_ key: String) -> String {
        let parts = key.split(separator: " ")
        guard parts.count >= 2, let blob = Data(base64Encoded: String(parts[1])) else { return key }
        let b64 = Data(SHA256.hash(data: blob)).base64EncodedString().replacingOccurrences(of: "=", with: "")
        return "\(parts[0]) SHA256:\(b64)"
    }

    // MARK: - the private key

    /// where the kept key is: the app's own, not backed up
    private static var keyFile: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("ssh", isDirectory: true).appendingPathComponent("key")
    }

    /// the kept key's text, or nil when there's none
    private func keyText() -> String? {
        guard !keyName.isEmpty else { return nil }
        return try? String(contentsOf: Self.keyFile, encoding: .utf8)
    }

    /// a key's passphrase asked until it opens the key (nil: given up)
    private func unlock(_ key: String, name: String, why: String = "", _ done: @escaping (String?) -> Void) {
        ask(.passphrase(name: name, why: why)) { [weak self] pass in
            guard let pass else { return done(nil) }
            if SSHShell.publicKey(key, passphrase: pass) != nil { return done(pass) }
            // (asked again once the alert that answered is gone)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 400_000_000)
                self?.unlock(key, name: name, why: "That passphrase does not open it", done)
            }
        }
    }

    /// a key file chosen in Files, kept as the key to log in with (a
    /// .ppk turned into OpenSSH's form, with the same passphrase)
    func chooseKey(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            keyNote = "\(url.lastPathComponent) could not be read."
            return
        }
        let name = url.lastPathComponent
        if !SSHShell.keyEncrypted(text) { return keep(text, name: name, passphrase: nil) }
        // (asked once Files' sheet is gone)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 400_000_000)
            self.unlock(text, name: name) { [weak self] pass in
                if let pass { self?.keep(text, name: name, passphrase: pass) }
            }
        }
    }

    /// a new Ed25519 key made and kept (no passphrase: it stays in the app)
    func makeKey() {
        guard let k = SSHShell.newKey() else {
            keyNote = "The key could not be made."
            return
        }
        store(k.key, name: "Ed25519 key made \(Date().formatted(date: .abbreviated, time: .omitted))", publicKey: k.publicKey)
        keyNote = "A new key is made. Add its public key to the server's ~/.ssh/authorized_keys."
    }

    /// a chosen key's text checked and kept, a .ppk in OpenSSH's form
    private func keep(_ text: String, name: String, passphrase: String?) {
        guard let pub = SSHShell.publicKey(text, passphrase: passphrase) else {
            keyNote = "\(name) is not a private key this app can read."
            return
        }
        var key = text
        let ppk = text.hasPrefix("PuTTY-User-Key-File-")
        if ppk {
            guard let k = SSHShell.exportKey(text, passphrase: passphrase, newPassphrase: passphrase) else {
                keyNote = "\(name) could not be turned into OpenSSH's form."
                return
            }
            key = k
        }
        store(key, name: name, publicKey: pub)
        keyNote = ppk ? "PuTTY's key \(name) is imported, in OpenSSH's form with the same passphrase." : "\(name) is kept as the key to log in with."
    }

    /// a key's text written to the app's own file, it named and used
    private func store(_ key: String, name: String, publicKey: String) {
        var file = Self.keyFile
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(key.utf8).write(to: file, options: [.atomic, .completeFileProtection])
            var v = URLResourceValues()
            v.isExcludedFromBackup = true
            try? file.setResourceValues(v)
        } catch {
            keyNote = "The key could not be kept: \(error.localizedDescription)"
            return
        }
        keyName = name
        keyPublic = publicKey
        defaults.set(name, forKey: "remoteKeyName")
        defaults.set(publicKey, forKey: "remoteKeyPublic")
        useKey = true
    }

    /// the kept key removed
    func forgetKey() {
        try? FileManager.default.removeItem(at: Self.keyFile)
        keyName = ""
        keyPublic = ""
        defaults.removeObject(forKey: "remoteKeyName")
        defaults.removeObject(forKey: "remoteKeyPublic")
        useKey = false
        keyNote = ""
    }

    /// a question to the user; one asked before is answered no
    private func ask(_ q: Question, _ answer: @escaping (String?) -> Void) {
        answerQuestion?(nil)
        answerQuestion = answer
        question = q
    }

    /// the user's answer to [question]: the password, "yes", or nil for no
    func answer(_ a: String?) {
        let f = answerQuestion
        answerQuestion = nil
        question = nil
        f?(a)
    }

    /// a Telnet or rlogin connection, its protocol in a LoginBridge
    private func startSocket(host: String, port: Int, user: String) {
        let kind = proto == .rlogin ? "rlogin" : "telnet"
        // (Network can't read urgent data apart: rlogin's comes in the stream)
        let l = LoginBridge(kind: kind, user: user, term: proto == .rlogin ? "ansi" : "ANSI",
                            cols: Int32(cols), rows: Int32(rows), inlineUrgent: true)
        login = l
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return ended("the port is not one") }
        let c = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        connection = c
        c.stateUpdateHandler = { [weak self, weak c] st in
            Task { @MainActor in
                guard let self, let c, c === self.connection else { return }
                switch st {
                case .ready:
                    self.status = "Connected"
                    l.connected()
                    self.pump()
                    self.receive(c)
                case .waiting(let e), .failed(let e):
                    self.ended("could not connect: \(e.localizedDescription)")
                default:
                    break
                }
            }
        }
        c.start(queue: .main)
    }

    /// the socket's next data, to the protocol and on to the screen
    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, done, error in
            Task { @MainActor in
                guard let self, c === self.connection, let l = self.login else { return }
                if let data, !data.isEmpty, let s = String(data: data, encoding: .isoLatin1) {
                    l.feed(latin1: s)
                    self.pump()
                }
                if done || error != nil {
                    self.ended(error.map { "the connection failed: \($0.localizedDescription)" } ?? "Connection closed by the server")
                } else {
                    self.receive(c)
                }
            }
        }
    }

    /// the protocol's screen octets shown, its output sent
    private func pump() {
        guard let l = login else { return }
        let screen = l.screen()
        if !screen.isEmpty, let d = screen.data(using: .isoLatin1) { terminal.feed(d) }
        let out = l.output()
        if !out.isEmpty, let d = out.data(using: .isoLatin1) { connection?.send(content: d, completion: .idempotent) }
    }

    // MARK: - the terminal

    /// what's typed, to the server
    private func send(_ d: Data) {
        guard running else { return }
        if let ssh {
            ssh.write(d)
        } else if let l = login, let s = String(data: d, encoding: .isoLatin1) {
            l.type(latin1: s)
            pump()
        }
    }

    /// the screen's new size, told to the server
    private func resized(_ c: Int, _ r: Int) {
        cols = c
        rows = r
        guard running else { return }
        ssh?.resize(cols: c, rows: r)
        if let l = login {
            l.resize(cols: Int32(c), rows: Int32(r))
            pump()
        }
    }

    /// a note of ours on the screen
    private func show(_ s: String) {
        terminal.feed(Data(s.utf8))
    }

    /// the connection over, with why (nil when the user asked)
    private func ended(_ why: String?) {
        guard running else { return }
        running = false
        if question != nil { answer(nil) }
        let s = ssh
        ssh = nil
        s?.close()
        connection?.cancel()
        connection = nil
        login = nil
        show("\r\n\u{1b}[0m[\(why ?? "Disconnected")]\r\n")
        status = why ?? "Disconnected"
    }
}
