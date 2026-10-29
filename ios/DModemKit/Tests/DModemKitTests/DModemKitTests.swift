// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemKit
import Foundation
import XCTest

/// DModemKit on the engine itself (Linux's host build, or Apple's
/// XCFramework): a session started on a peer line (no SIP needed) answering
/// AT commands, its console and status; a dial handed to the app and
/// turned down busy; stopped. The settings as the Android app has them.
final class DModemKitTests: XCTestCase {
    /// [cond] within [secs]
    func waitFor(_ secs: Double = 10, _ cond: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(secs)
        while Date() < end {
            if cond() { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return cond()
    }

    final class Lines: EngineListener, @unchecked Sendable {
        let session: ModemSession
        var dial = ""
        var fd: Int32 = -1
        let lock = NSLock()
        /// a listener passing the engine's news on to [s], but for its peer lines
        init(_ s: ModemSession) { session = s }
        /// the log line to the session
        func engineLog(_ line: String) { session.engineLog(line) }
        /// the status to the session
        func engineStatus(_ status: ModemStatus) { session.engineStatus(status) }
        /// the exit to the session
        func engineExited(_ result: Int) { session.engineExited(result) }
        /// the peer line's dial and samples kept for the test (not closed)
        func engineLine(caller: Bool, dial: String, fd: Int32) {
            lock.lock(); self.dial = dial; self.fd = fd; lock.unlock()
        }
    }

    /// a session on a peer line starts, answers AT commands, logs and changes, ignores a second start, and stops
    func testASessionOnAPeerLine() {
        signal(SIGPIPE, SIG_IGN)
        let session = ModemSession()
        var changes = 0
        session.onChange = { changes += 1 }
        var settings = ModemSettings()
        settings.line = .wifi
        settings.initCommands = "ATE0"
        // (the modem's log at level 1: its commands, its states)
        settings.logLevel = 1
        XCTAssertTrue(session.start(settings))
        XCTAssertEqual(session.run, .running)
        XCTAssertTrue(waitFor { session.console.contains("OK") }, session.console)
        session.command("ATI3")
        XCTAssertTrue(waitFor { session.console.contains("peer") }, session.console)
        // the log came through
        XCTAssertTrue(waitFor { !session.log.isEmpty })
        XCTAssertGreaterThan(changes, 0)
        // a second start while it runs: nothing new
        XCTAssertTrue(session.start(settings))
        session.stop()
        XCTAssertEqual(session.run, .stopped)
        XCTAssertFalse(Engine.shared.running)
    }

    /// a dial is handed to the listener with its number, BUSY comes back for a busy far end, and closing the DTE stops the modem
    func testADialHandedToTheApp() throws {
        signal(SIGPIPE, SIG_IGN)
        let session = ModemSession()
        let lines = Lines(session)
        var config = EngineConfig()
        config.peer = true
        let dte = try Engine.shared.start(config, listener: lines)
        var text = ""
        let tl = NSLock()
        dte.read(onData: { d in tl.lock(); text += String(decoding: d, as: UTF8.self); tl.unlock() })
        dte.command("ATE0X3D5551234")
        XCTAssertTrue(waitFor { lines.lock.lock(); defer { lines.lock.unlock() }; return lines.fd >= 0 })
        XCTAssertEqual(lines.dial, "5551234")
        // the far end busy: BUSY to the DTE
        Engine.shared.peerEvent("FAILED 486")
        // (and its line closed, as the app's PeerLine ends a failed call)
        close(lines.fd)
        XCTAssertTrue(waitFor { tl.lock(); defer { tl.unlock() }; return text.contains("BUSY") }, text)
        // the DTE closed: the modem stops by itself
        dte.close()
        XCTAssertTrue(waitFor { !Engine.shared.running })
        // (its thread joined)
        Engine.shared.stop()
    }

    /// a session's dial on a peer line goes to [ModemSession.onLine], and the line gone ends it
    func testThePeerLineGoesToTheApp() {
        signal(SIGPIPE, SIG_IGN)
        let session = ModemSession()
        let got = NSLock()
        var line: (Bool, String, Int32)?
        session.onLine = { caller, dial, fd in got.lock(); line = (caller, dial, fd); got.unlock() }
        var settings = ModemSettings()
        settings.line = .wifi
        settings.initCommands = "ATE0X3"
        XCTAssertTrue(session.start(settings))
        XCTAssertTrue(waitFor { session.console.contains("OK") })
        session.command("ATD192*168*1*20")
        XCTAssertTrue(waitFor { got.lock(); defer { got.unlock() }; return line != nil })
        XCTAssertEqual(line?.0, true)
        XCTAssertEqual(line?.1, "192*168*1*20")
        // the app's line gone: the call's NO CARRIER
        Engine.shared.peerEvent("FAILED 480")
        close(line!.2)
        XCTAssertTrue(waitFor { session.console.contains("NO ") }, session.console)
        session.stop()
    }

    /// the DTE lent to a job gets all the modem says (one job at a time, none while it's off), and its leftover goes back to the terminal
    func testTheDteLentToAJob() {
        signal(SIGPIPE, SIG_IGN)
        let session = ModemSession()
        var terminal = ""
        let tl = NSLock()
        session.onData = { d in tl.lock(); terminal += String(decoding: d, as: UTF8.self); tl.unlock() }
        // (the modem off: nothing to lend)
        XCTAssertFalse(session.claim { _ in })
        var settings = ModemSettings()
        settings.line = .wifi
        settings.initCommands = "ATE0"
        XCTAssertTrue(session.start(settings))
        XCTAssertTrue(waitFor { tl.lock(); defer { tl.unlock() }; return terminal.contains("OK") })
        var job = ""
        let jl = NSLock()
        XCTAssertTrue(session.claim { d in jl.lock(); job += String(decoding: d, as: UTF8.self); jl.unlock() })
        XCTAssertTrue(session.claimed)
        XCTAssertFalse(session.claim { _ in })
        tl.lock(); terminal = ""; tl.unlock()
        session.command("ATI3")
        XCTAssertTrue(waitFor { jl.lock(); defer { jl.unlock() }; return job.contains("peer") }, job)
        tl.lock(); XCTAssertEqual(terminal, ""); tl.unlock()
        // back to the terminal, with what the job left
        session.release(leftover: Data("left\r\n".utf8))
        XCTAssertFalse(session.claimed)
        tl.lock(); XCTAssertEqual(terminal, "left\r\n"); tl.unlock()
        session.stop()
    }

    /// with no line, the modem starts without a SIP account, dials get NO DIALTONE, and the setting is kept
    func testNoLine() {
        signal(SIGPIPE, SIG_IGN)
        let session = ModemSession()
        var settings = ModemSettings()
        settings.line = .none
        settings.initCommands = "ATE0"
        // (no SIP account: it starts all the same)
        XCTAssertTrue(settings.complete)
        XCTAssertTrue(session.start(settings))
        XCTAssertTrue(waitFor { session.console.contains("OK") }, session.console)
        // a dial: nothing plugged in
        session.command("ATX3D5551234")
        XCTAssertTrue(waitFor { session.console.contains("NO DIALTONE") }, session.console)
        // still answering commands
        session.command("ATI3")
        XCTAssertTrue(waitFor { session.console.contains("peer") }, session.console)
        session.stop()
        // the setting kept
        XCTAssertEqual(try JSONDecoder().decode(ModemSettings.self, from: JSONEncoder().encode(settings)).line, .none)
    }

    /// without a SIP account (on a SIP line) the session doesn't start, and says why
    func testIncompleteSettingsDontStart() {
        let session = ModemSession()
        XCTAssertFalse(session.start(ModemSettings()))
        XCTAssertEqual(session.error, "set up a SIP account first")
        XCTAssertEqual(session.run, .stopped)
    }

    /// user:password@domain parsed (an @ in the password, a port in the domain); one without a user, password or domain isn't
    func testLogins() {
        let a = ModemSettings.parseLogin("alice:secret@sip.example.com")
        XCTAssertEqual(a?.user, "alice"); XCTAssertEqual(a?.password, "secret"); XCTAssertEqual(a?.domain, "sip.example.com")
        let b = ModemSettings.parseLogin("bob:p@ss@pbx.local:5070")
        XCTAssertEqual(b?.password, "p@ss"); XCTAssertEqual(b?.domain, "pbx.local:5070")
        XCTAssertEqual(ModemSettings.parseLogin("u:@h")?.password, "")
        XCTAssertNil(ModemSettings.parseLogin("alice@sip.example.com"))
        XCTAssertNil(ModemSettings.parseLogin("alice:secret"))
        XCTAssertNil(ModemSettings.parseLogin(":x@h"))
        XCTAssertNil(ModemSettings.parseLogin("u:x@"))
    }

    /// settings kept with their secrets apart, a cleared secret removed, an old version's moved out of the JSON, missing fields defaulted
    func testSettingsKeptAndOldOnesRead() throws {
        let defaults = UserDefaults(suiteName: "dmodemkit-test-\(UUID())")!
        let secrets = MemorySecrets()
        let store = SettingsStore(defaults: defaults, secrets: secrets)
        XCTAssertEqual(store.settings, ModemSettings())
        var s = ModemSettings()
        s.user = "alice"; s.password = "pw1"; s.domain = "sip.example.com"; s.line = .wifi; s.peerPin = "1234"
        s.ispPassword = "isp"; s.faxIdent = "+1 555 0100"
        store.settings = s
        XCTAssertEqual(store.settings, s)
        XCTAssertTrue(s.complete)
        // the secrets apart, not in the JSON
        let json = String(decoding: defaults.data(forKey: "modemSettings")!, as: UTF8.self)
        XCTAssertFalse(json.contains("pw1") || json.contains("1234") || json.contains("isp\""), json)
        XCTAssertEqual(secrets.get("sip-password"), "pw1")
        XCTAssertEqual(secrets.get("peer-pin"), "1234")
        // a secret cleared: gone from the store too
        s.peerPin = ""
        store.settings = s
        XCTAssertNil(secrets.get("peer-pin"))
        // an older version's password in the JSON: moved to the secrets
        defaults.set(Data(#"{"user":"u","password":"old","domain":"d"}"#.utf8), forKey: "modemSettings")
        XCTAssertEqual(store.settings.password, "old")
        XCTAssertEqual(secrets.get("sip-password"), "old")
        XCTAssertFalse(String(decoding: defaults.data(forKey: "modemSettings")!, as: UTF8.self).contains("old"))
        XCTAssertEqual(store.settings.password, "old")
        // settings from before a field was added: its default
        defaults.set(Data(#"{"user":"u","domain":"d"}"#.utf8), forKey: "modemSettings")
        XCTAssertEqual(store.settings.user, "u")
        XCTAssertTrue(store.settings.faxEcm)
        XCTAssertEqual(store.settings.faxMaxBps, 14400)
        XCTAssertEqual(store.settings.initCommands, "ATX3")
        XCTAssertTrue(store.settings.t38)
        XCTAssertEqual(ModemSettings().engineConfig(dns: ["1.1.1.1"]).dns, ["1.1.1.1"])
    }

    /// the label of the last CONNECT's protocols, empty for one with none
    func testWhatTheConnectSaid() {
        XCTAssertEqual(ConnectInfo.label("ATD5550002\r\nCONNECT 1200/CVM/LAPM\r\n"), "V.cvm · LAPM")
        XCTAssertEqual(ConnectInfo.label("CONNECT 48000/V92/LAPM/V44\r\nhello"), "V.92 · LAPM · V.44")
        XCTAssertEqual(ConnectInfo.label("CONNECT 300\r\n"), "")
        XCTAssertEqual(ConnectInfo.label("OK\r\n"), "")
        XCTAssertEqual(ConnectInfo.label("CONNECT 9600/V32\r\nNO CARRIER\r\nCONNECT 1200/CVM\r\n"), "V.cvm")
    }
}
