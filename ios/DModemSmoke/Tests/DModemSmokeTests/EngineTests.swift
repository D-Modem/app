// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemEngine
import Foundation
import XCTest

/// The modem through its C API from Swift, on an Apple system: started on
/// a peer line, AT commands answered, a dial handing its line to Swift,
/// BUSY from Swift, the modem ended by closing its DTE.
final class EngineTests: XCTestCase {
    /// what the callbacks saw (from the modem's threads)
    final class Seen {
        let lock = NSLock()
        var lineFd: Int32 = -1
        var dial = ""
        var exits = 0
        var speakerOn = false
    }

    /// text from fd until it holds want, or secs
    func expect(_ fd: Int32, _ want: String, secs: Double = 5) -> Bool {
        var text = ""
        let end = Date().addingTimeInterval(secs)
        var buf = [UInt8](repeating: 0, count: 4096)
        while Date() < end {
            var p = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            if poll(&p, 1, 100) > 0 {
                let n = read(fd, &buf, buf.count)
                if n <= 0 { break }
                text += String(decoding: buf[0..<n], as: UTF8.self)
                if text.contains(want) { return true }
            }
        }
        print("waited for \(want), got \(text)")
        return false
    }

    func put(_ fd: Int32, _ s: String) {
        _ = s.withCString { write(fd, $0, strlen($0)) }
    }

    func testThePeerLine() throws {
        signal(SIGPIPE, SIG_IGN)
        let seen = Seen()
        var cb = dmodem_callbacks()
        cb.ctx = Unmanaged.passRetained(seen).toOpaque()
        cb.line = { ctx, caller, dial, fd in
            let s = Unmanaged<Seen>.fromOpaque(ctx!).takeUnretainedValue()
            s.lock.lock(); s.lineFd = fd; s.dial = String(cString: dial!); s.lock.unlock()
        }
        cb.exited = { ctx, _ in
            let s = Unmanaged<Seen>.fromOpaque(ctx!).takeUnretainedValue()
            s.lock.lock(); s.exits += 1; s.lock.unlock()
        }
        cb.speaker = { ctx, level in
            let s = Unmanaged<Seen>.fromOpaque(ctx!).takeUnretainedValue()
            if level > 0 { s.lock.lock(); s.speakerOn = true; s.lock.unlock() }
        }
        dmodem_set_callbacks(&cb)

        var c = dmodem_config()
        dmodem_config_init(&c)
        c.peer = 1
        let dte = dmodem_start(&c)
        XCTAssertGreaterThanOrEqual(dte, 0)
        XCTAssertEqual(dmodem_running(), 1)

        put(dte, "ATE0\r")
        XCTAssertTrue(expect(dte, "OK"))
        put(dte, "ATI3\r")
        XCTAssertTrue(expect(dte, "peer"))

        // a dial: its line to us; we say busy
        put(dte, "ATX3D5551234\r")
        var fd: Int32 = -1
        for _ in 0..<50 where fd < 0 {
            usleep(100_000)
            seen.lock.lock(); fd = seen.lineFd; seen.lock.unlock()
        }
        XCTAssertGreaterThanOrEqual(fd, 0)
        XCTAssertTrue(seen.dial.contains("5551234"))
        XCTAssertTrue(seen.speakerOn)
        dmodem_peer_event("FAILED 486")
        close(fd)
        XCTAssertTrue(expect(dte, "BUSY", secs: 10))

        // the DTE closed: the modem ends
        close(dte)
        for _ in 0..<50 where dmodem_running() != 0 { usleep(100_000) }
        XCTAssertEqual(dmodem_running(), 0)
        seen.lock.lock(); XCTAssertEqual(seen.exits, 1); seen.lock.unlock()
        dmodem_stop()
    }
}
