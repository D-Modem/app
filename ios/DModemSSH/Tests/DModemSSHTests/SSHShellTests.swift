// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemSSH
import Foundation
import XCTest

/// A login to a real sshd, when DMODEM_SSH_TEST names one as
/// host:port:user:password (else skipped).
final class SSHShellTests: XCTestCase {
    /// DMODEM_SSH_TEST's host, port, user and password, or the test skipped
    private func server() throws -> (String, Int, String, String) {
        guard let spec = ProcessInfo.processInfo.environment["DMODEM_SSH_TEST"] else {
            throw XCTSkip("DMODEM_SSH_TEST is not set")
        }
        let p = spec.split(separator: ":", maxSplits: 3).map(String.init)
        return (p[0], Int(p[1]) ?? 22, p[2], p[3])
    }

    /// a login, a command's output read, a resize seen, the exit status told
    func testShell() throws {
        let (host, port, user, password) = try server()
        let shell = SSHShell(host: host, port: port, user: user, term: "vt100", cols: 100, rows: 30)
        let lock = NSLock()
        var out = Data()
        var hostKey = ""
        var asks: [String] = []
        let closed = expectation(description: "closed")
        var why: String?
        shell.verifyHostKey = { k, ok in
            lock.withLock { hostKey = k }
            ok(true)
        }
        shell.askPassword = { reason, done in
            lock.withLock { asks.append(reason) }
            // (the first wrong, to see it asked again)
            done(asks.count == 1 ? "wrong" : password)
        }
        shell.onData = { d in
            lock.withLock { out.append(d) }
        }
        shell.onClose = { w in
            why = w
            closed.fulfill()
        }
        shell.connect()
        // the shell's prompt, then the commands
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline && lock.withLock({ out.isEmpty }) { Thread.sleep(forTimeInterval: 0.1) }
        shell.resize(cols: 120, rows: 40)
        Thread.sleep(forTimeInterval: 0.5)
        shell.write(Data("echo TERM=$TERM; stty size; exit 3\r".utf8))
        wait(for: [closed], timeout: 20)
        let text = String(decoding: lock.withLock { out }, as: UTF8.self)
        XCTAssertTrue(hostKey.hasPrefix("ssh-ed25519 ") || hostKey.hasPrefix("ecdsa-"), hostKey)
        XCTAssertEqual(asks, ["", "The password was not accepted"])
        XCTAssertTrue(text.contains("TERM=vt100"), text)
        XCTAssertTrue(text.contains("40 120"), text)
        XCTAssertEqual(why, "the shell exited with status 3")
    }

    /// a refused host key closes the connection, saying so
    func testRefusedHostKey() throws {
        let (host, port, user, _) = try server()
        let shell = SSHShell(host: host, port: port, user: user)
        let closed = expectation(description: "closed")
        var why: String?
        shell.verifyHostKey = { _, ok in ok(false) }
        shell.onClose = { w in
            why = w
            closed.fulfill()
        }
        shell.connect()
        wait(for: [closed], timeout: 20)
        XCTAssertEqual(why, "the host's key was not accepted")
    }

    /// a close by the user is told as nil
    func testCloseByUser() throws {
        let (host, port, user, password) = try server()
        let shell = SSHShell(host: host, port: port, user: user)
        let closed = expectation(description: "closed")
        let opened = expectation(description: "data")
        opened.assertForOverFulfill = false
        var why: String? = "unset"
        shell.verifyHostKey = { _, ok in ok(true) }
        shell.askPassword = { _, done in done(password) }
        shell.onData = { _ in opened.fulfill() }
        shell.onClose = { w in
            why = w
            closed.fulfill()
        }
        shell.connect()
        wait(for: [opened], timeout: 20)
        shell.close()
        wait(for: [closed], timeout: 20)
        XCTAssertNil(why)
    }

    /// a new key, its public line read again, written with a passphrase
    /// and read with it (and not without)
    func testKeys() throws {
        let k = try XCTUnwrap(SSHShell.newKey())
        XCTAssertTrue(k.publicKey.hasPrefix("ssh-ed25519 "), k.publicKey)
        XCTAssertFalse(SSHShell.keyEncrypted(k.key))
        XCTAssertEqual(SSHShell.publicKey(k.key), k.publicKey)
        let locked = try XCTUnwrap(SSHShell.exportKey(k.key, passphrase: nil, newPassphrase: "sesame"))
        XCTAssertTrue(SSHShell.keyEncrypted(locked))
        XCTAssertNil(SSHShell.publicKey(locked, passphrase: "wrong"))
        XCTAssertEqual(SSHShell.publicKey(locked, passphrase: "sesame"), k.publicKey)
        XCTAssertNil(SSHShell.publicKey("not a key"))
    }

    /// a login by a key in a file DMODEM_SSH_TEST_PPK names (PuTTY's .ppk,
    /// no passphrase), turned into OpenSSH's form too: no password asked
    func testKeyLogin() throws {
        let (host, port, user, _) = try server()
        guard let path = ProcessInfo.processInfo.environment["DMODEM_SSH_TEST_PPK"] else {
            throw XCTSkip("DMODEM_SSH_TEST_PPK is not set")
        }
        let ppk = try String(contentsOfFile: path, encoding: .utf8)
        let openssh = try XCTUnwrap(SSHShell.exportKey(ppk, passphrase: nil, newPassphrase: nil))
        XCTAssertTrue(openssh.hasPrefix("-----BEGIN OPENSSH PRIVATE KEY-----"), openssh)
        for key in [ppk, openssh] {
            let shell = SSHShell(host: host, port: port, user: user)
            let closed = expectation(description: "closed")
            let lock = NSLock()
            var out = Data()
            var why: String?
            shell.privateKey = (key, nil)
            shell.verifyHostKey = { _, ok in ok(true) }
            shell.askPassword = { _, done in
                XCTFail("a password was asked")
                done(nil)
            }
            shell.onData = { d in lock.withLock { out.append(d) } }
            shell.onClose = { w in
                why = w
                closed.fulfill()
            }
            shell.connect()
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline && lock.withLock({ out.isEmpty }) { Thread.sleep(forTimeInterval: 0.1) }
            shell.write(Data("exit 4\r".utf8))
            wait(for: [closed], timeout: 20)
            XCTAssertEqual(why, "the shell exited with status 4")
        }
    }
}
