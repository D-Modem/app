// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
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
        init(_ s: ModemSession) { session = s }
        func engineLog(_ line: String) { session.engineLog(line) }
        func engineStatus(_ status: ModemStatus) { session.engineStatus(status) }
        func engineExited(_ result: Int) { session.engineExited(result) }
        func engineLine(caller: Bool, dial: String, fd: Int32) {
            lock.lock(); self.dial = dial; self.fd = fd; lock.unlock()
        }
    }

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

    func testIncompleteSettingsDontStart() {
        let session = ModemSession()
        XCTAssertFalse(session.start(ModemSettings()))
        XCTAssertEqual(session.error, "set up a SIP account first")
        XCTAssertEqual(session.run, .stopped)
    }

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

    func testSettingsKeptAndOldOnesRead() throws {
        let defaults = UserDefaults(suiteName: "dmodemkit-test-\(UUID())")!
        let store = SettingsStore(defaults: defaults)
        XCTAssertEqual(store.settings, ModemSettings())
        var s = ModemSettings()
        s.user = "alice"; s.domain = "sip.example.com"; s.line = .wifi; s.peerPin = "1234"
        store.settings = s
        XCTAssertEqual(store.settings, s)
        XCTAssertTrue(s.complete)
        // settings from before a field was added: its default
        defaults.set(Data(#"{"user":"u","domain":"d"}"#.utf8), forKey: "modemSettings")
        XCTAssertEqual(store.settings.user, "u")
        XCTAssertEqual(store.settings.initCommands, "ATX3")
        XCTAssertTrue(store.settings.t38)
        XCTAssertEqual(ModemSettings().engineConfig(dns: ["1.1.1.1"]).dns, ["1.1.1.1"])
    }

    func testWhatTheConnectSaid() {
        XCTAssertEqual(ConnectInfo.label("ATD5550002\r\nCONNECT 1200/CVM/LAPM\r\n"), "V.cvm · LAPM")
        XCTAssertEqual(ConnectInfo.label("CONNECT 48000/V92/LAPM/V44\r\nhello"), "V.92 · LAPM · V.44")
        XCTAssertEqual(ConnectInfo.label("CONNECT 300\r\n"), "")
        XCTAssertEqual(ConnectInfo.label("OK\r\n"), "")
        XCTAssertEqual(ConnectInfo.label("CONNECT 9600/V32\r\nNO CARRIER\r\nCONNECT 1200/CVM\r\n"), "V.cvm")
    }
}
