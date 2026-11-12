// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import CDModemSSH
import Foundation

/// An SSH login's interactive shell, on libdmodemssh (native-ssh, on
/// libssh): the host's key checked by the caller ([verifyHostKey], its
/// OpenSSH form), the user logged in by a private key ([privateKey], when
/// one is given), else by password ([askPassword], asked again when one is
/// refused, three times at most) or the server's questions
/// ([askQuestions], keyboard-interactive); a pty of [term] in the
/// screen's size and a shell on it. What the server says (stdout and
/// stderr) goes to [onData]; [write] sends what is typed; [resize] the
/// window's new size. [onClose] once, with why (nil when it was asked to).
///
/// The callbacks are made on the connection's own thread: hop to the
/// main one to touch views. Host keys and the user's: Ed25519, ECDSA and
/// RSA.
public final class SSHShell: @unchecked Sendable {
    public let host: String
    public let port: Int
    public let user: String
    public let term: String

    public var onData: (Data) -> Void = { _ in }
    public var onClose: (String?) -> Void = { _ in }
    /// the host's key ("ssh-ed25519 AAAA..."): call back with true to go on
    public var verifyHostKey: (String, @escaping (Bool) -> Void) -> Void = { _, ok in ok(false) }
    /// the user's password, asked ("" when it's the first time, else why
    /// the last was refused): call back with nil to give up
    public var askPassword: (String, @escaping (String?) -> Void) -> Void = { _, done in done(nil) }
    /// the server's questions (keyboard-interactive): its instruction and
    /// each question with whether its answer may be shown; call back with
    /// the answers, or nil to give up. Unset: passwords are asked by
    /// [askPassword] instead
    public var askQuestions: ((String, [(String, Bool)], @escaping ([String]?) -> Void) -> Void)?
    /// a private key's text (OpenSSH's, PEM or PuTTY's .ppk) to log in
    /// with first, and its passphrase
    public var privateKey: (key: String, passphrase: String?)?

    private var cols: Int
    private var rows: Int
    private let lock = NSLock()
    private let s: OpaquePointer?
    private var shell: Int32 = 0
    private var started = false
    private var asked = false
    private var exitStatus = 0
    private var triedKey = false
    private var asks = 0

    /// a shell to log in to: nothing is done until [connect]
    public init(host: String, port: Int = 22, user: String, term: String = "xterm-256color", cols: Int = 80, rows: Int = 24) {
        self.host = host
        self.port = port
        self.user = user
        self.term = term
        self.cols = cols
        self.rows = rows
        s = dmc_ssh_new(host, Int32(port), user)
    }

    /// the connection freed
    deinit {
        // (its thread holds the shell until the connection's over)
        if let s { dmc_ssh_free(s) }
    }

    /// connect, log in and start the shell, on the connection's thread
    public func connect() {
        guard let s else { return onClose("could not start SSH") }
        guard !started else { return }
        started = true
        let t = Thread { self.run(s) }
        t.name = "ssh-\(host)"
        t.start()
    }

    /// the connection's life: each event acted on until it's over
    private func run(_ s: OpaquePointer) {
        var ev = dmc_ssh_event()
        while dmc_ssh_poll(s, 1000, &ev) >= 0 {
            if ev.type == 0 { continue }
            let data: Data? = ev.data.map { Data(bytes: $0, count: Int(ev.len)) }
            let text = data.map { String(decoding: $0, as: UTF8.self) }
            let type = Int(ev.type)
            ev.type = 0
            switch type {
            case DMC_SSH_EV_HOSTKEY:
                verifyHostKey(text ?? "") { ok in dmc_ssh_trust(s, ok ? 1 : 0, 0) }
            case DMC_SSH_EV_AUTH:
                login(s, methods: Int(ev.value), why: text ?? "")
            case DMC_SSH_EV_PROMPTS:
                questions(s, count: Int(ev.value))
            case DMC_SSH_EV_READY:
                let (c, r) = lock.withLock { (cols, rows) }
                let ch = dmc_ssh_shell(s, term, Int32(c), Int32(r), 0)
                lock.withLock { shell = ch }
            case DMC_SSH_EV_OPEN:
                if ev.value == 0 { dmc_ssh_disconnect(s) }
            case DMC_SSH_EV_DATA:
                if ev.channel == lock.withLock({ shell }), let data { onData(data) }
            case DMC_SSH_EV_EXIT:
                if ev.channel == lock.withLock({ shell }) { exitStatus = Int(ev.value) }
            case DMC_SSH_EV_CLOSE:
                // (the shell's over: so is the connection)
                if ev.channel == lock.withLock({ shell }) {
                    lock.withLock { shell = 0 }
                    dmc_ssh_disconnect(s)
                }
            case DMC_SSH_EV_INCOMING:
                dmc_ssh_close(s, ev.channel)
            case DMC_SSH_EV_CLOSED:
                let byUser = lock.withLock { asked }
                if text != nil {
                    onClose(text)
                } else if byUser {
                    onClose(nil)
                } else if exitStatus != 0 {
                    onClose("the shell exited with status \(exitStatus)")
                } else {
                    onClose("Connection closed")
                }
                return
            default:
                break
            }
        }
    }

    /// the next login: the key first, then the server's questions or a
    /// password (three times at most)
    private func login(_ s: OpaquePointer, methods: Int, why: String) {
        if let k = privateKey, !triedKey, methods & DMC_SSH_AUTH_PUBLICKEY != 0 {
            triedKey = true
            if dmc_ssh_auth_key(s, k.key, k.passphrase) == 0 { return }
        }
        asks += 1
        let interactive = methods & DMC_SSH_AUTH_INTERACTIVE != 0
        let password = methods & DMC_SSH_AUTH_PASSWORD != 0
        if asks > 3 {
            dmc_ssh_auth_cancel(s)
        } else if interactive && (askQuestions != nil || !password) {
            dmc_ssh_auth_interactive(s)
        } else if password {
            askPassword(asks == 1 ? "" : (why.isEmpty ? "The password was not accepted" : why)) { pw in
                if let pw { dmc_ssh_auth_password(s, pw) } else { dmc_ssh_auth_cancel(s) }
            }
        } else {
            dmc_ssh_auth_cancel(s)
        }
    }

    /// a round of the server's questions put to the user
    private func questions(_ s: OpaquePointer, count: Int) {
        if count == 0 { return dmc_ssh_answered(s) }
        var qs: [(String, Bool)] = []
        for i in 0..<count {
            var echo: Int32 = 0
            qs.append((String(cString: dmc_ssh_prompt(s, Int32(i), &echo)), echo != 0))
        }
        let instruction = String(cString: dmc_ssh_instruction(s))
        let answer: ([String]?) -> Void = { a in
            guard let a else { return dmc_ssh_auth_cancel(s) }
            for (i, x) in a.enumerated() { dmc_ssh_answer(s, Int32(i), x) }
            dmc_ssh_answered(s)
        }
        if let askQuestions {
            askQuestions(instruction, qs, answer)
        } else {
            askPassword(asks <= 1 ? "" : "The password was not accepted") { pw in answer(pw.map { [$0] }) }
        }
    }

    /// what's typed, to the shell
    public func write(_ data: Data) {
        guard let s, !data.isEmpty else { return }
        let ch = lock.withLock { shell }
        guard ch > 0 else { return }
        data.withUnsafeBytes { b in
            _ = dmc_ssh_write(s, ch, b.baseAddress!.assumingMemoryBound(to: CChar.self), Int32(b.count))
        }
    }

    /// the screen's new size, to the server
    public func resize(cols: Int, rows: Int) {
        guard let s else { return }
        let ch: Int32? = lock.withLock {
            guard cols != self.cols || rows != self.rows else { return nil }
            self.cols = cols
            self.rows = rows
            return shell
        }
        if let ch, ch > 0 { dmc_ssh_resize(s, ch, Int32(cols), Int32(rows)) }
    }

    // MARK: - private keys

    /// whether a private key's text (OpenSSH's, PEM or PuTTY's .ppk) needs
    /// a passphrase
    public static func keyEncrypted(_ key: String) -> Bool {
        dmc_ssh_key_encrypted(key) == 1
    }

    /// a private key's public line ("ssh-ed25519 AAAA..."), or nil when it
    /// can't be read with that passphrase (or isn't a key)
    public static func publicKey(_ key: String, passphrase: String? = nil) -> String? {
        var out: UnsafeMutablePointer<CChar>?
        guard dmc_ssh_key_public(key, passphrase, &out) == 0, let out else { return nil }
        defer { dmc_ssh_free_string(out) }
        return String(cString: out).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// a private key written again in OpenSSH's form (a .ppk turned into
    /// one), with a new passphrase (nil or "": none); nil when it can't be
    /// read with that passphrase
    public static func exportKey(_ key: String, passphrase: String?, newPassphrase: String?) -> String? {
        var out: UnsafeMutablePointer<CChar>?
        guard dmc_ssh_key_export(key, passphrase, newPassphrase, &out) == 0, let out else { return nil }
        defer { dmc_ssh_free_string(out) }
        return String(cString: out)
    }

    /// a new Ed25519 key with no passphrase: its private text and public
    /// line, or nil
    public static func newKey() -> (key: String, publicKey: String)? {
        var key: UnsafeMutablePointer<CChar>?
        var pub: UnsafeMutablePointer<CChar>?
        guard dmc_ssh_keygen("ed25519", 0, nil, &key, &pub) == 0, let key, let pub else { return nil }
        defer {
            dmc_ssh_free_string(key)
            dmc_ssh_free_string(pub)
        }
        return (String(cString: key), String(cString: pub).trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// the connection closed by the user
    public func close() {
        guard let s else { return }
        lock.withLock { asked = true }
        if started {
            dmc_ssh_disconnect(s)
        } else {
            onClose(nil)
        }
    }
}
