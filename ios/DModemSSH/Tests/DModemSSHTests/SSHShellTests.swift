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
}
