// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation

/// The modem as the app has it: started with the settings, its status, its
/// log and its console (what it said, for Home's last lines and the
/// CONNECT's label), its DTE's data to whoever reads it (the terminal).
/// Changes go to [onChange] (on the engine's threads: the app's model
/// takes them to the main one).
public final class ModemSession: EngineListener, @unchecked Sendable {
    public enum Run: Sendable { case stopped, running }

    private let lock = NSLock()
    private var _run = Run.stopped
    private var _status = ModemStatus()
    private var _log: [String] = []
    private var _console = ""
    private var _error: String?
    private var dte: DTE?

    /// the most of the log and the console kept
    public var logMax = 2000
    public var consoleMax = 16 * 1024

    /// something changed (run, status, log, console, error)
    public var onChange: (() -> Void)?
    /// the DTE's data (the terminal's), as it comes
    public var onData: ((Data) -> Void)?
    /// the speaker's level and sound (the app's audio)
    public var onSpeaker: ((Int) -> Void)?
    public var onSound: ((UnsafeBufferPointer<Int16>) -> Void)?
    /// a peer line's modem off hook (the app's line to the other phone takes it): dialling [dial] if
    /// caller; fd its samples, the handler's to close. Without one the line is closed (the dial fails)
    public var onLine: ((_ caller: Bool, _ dial: String, _ fd: Int32) -> Void)?

    private let engine: Engine

    public init(engine: Engine = .shared) {
        self.engine = engine
    }

    public var run: Run { lock.lock(); defer { lock.unlock() }; return _run }
    public var status: ModemStatus { lock.lock(); defer { lock.unlock() }; return _status }
    public var log: [String] { lock.lock(); defer { lock.unlock() }; return _log }
    public var console: String { lock.lock(); defer { lock.unlock() }; return _console }
    /// why it last stopped by itself, or didn't start
    public var error: String? { lock.lock(); defer { lock.unlock() }; return _error }

    /// "V.cvm · LAPM": the last CONNECT's, while online
    public var connectLabel: String { status.link == .online ? ConnectInfo.label(console) : "" }

    /// the modem started with [settings]; its start commands sent. False (and [error]) if it couldn't
    @discardableResult
    public func start(_ settings: ModemSettings, dns: [String] = [], dataFile: String = "") -> Bool {
        if run == .running { return true }
        guard settings.complete else {
            setError("set up a SIP account first")
            return false
        }
        do {
            let d = try engine.start(settings.engineConfig(dns: dns, dataFile: dataFile), listener: self)
            lock.lock()
            dte = d
            _run = .running
            _status = ModemStatus()
            _error = nil
            lock.unlock()
            d.read(onData: { [weak self] data in self?.received(data) })
            let start = settings.startCommands()
            if !start.isEmpty { d.command(start) }
            changed()
            return true
        } catch {
            setError("the modem didn't start (the log says why)")
            return false
        }
    }

    /// the modem stopped: its call hung up
    public func stop() {
        lock.lock()
        let d = dte
        dte = nil
        lock.unlock()
        engine.stop()
        d?.close()
        lock.lock(); _run = .stopped; _status = ModemStatus(); lock.unlock()
        changed()
    }

    /// to the modem: data, or (with its CR) an AT command
    @discardableResult
    public func send(_ data: Data) -> Bool {
        lock.lock(); let d = dte; lock.unlock()
        return d?.write(data) ?? false
    }

    @discardableResult
    public func command(_ cmd: String) -> Bool { send(Data((cmd + "\r").utf8)) }

    /// hang up: +++ with its guard times, then ATH (blocking, about 2.5 s)
    public func hangUp() {
        Thread.sleep(forTimeInterval: 1.1)
        send(Data("+++".utf8))
        Thread.sleep(forTimeInterval: 1.3)
        command("ATH")
    }

    private func received(_ data: Data) {
        let text = String(decoding: data, as: UTF8.self)
        lock.lock()
        _console += text
        if _console.count > consoleMax { _console = String(_console.suffix(consoleMax / 2)) }
        lock.unlock()
        onData?(data)
        changed()
    }

    private func setError(_ e: String) {
        lock.lock(); _error = e; lock.unlock()
        changed()
    }

    private func changed() { onChange?() }

    // EngineListener

    public func engineLog(_ line: String) {
        lock.lock()
        _log.append(line)
        if _log.count > logMax { _log.removeFirst(_log.count - logMax) }
        lock.unlock()
        changed()
    }

    public func engineStatus(_ status: ModemStatus) {
        lock.lock(); _status = status; lock.unlock()
        changed()
    }

    public func engineExited(_ result: Int) {
        lock.lock()
        let wasRunning = _run == .running && dte != nil
        _run = .stopped
        _status = ModemStatus()
        if result != 0 && wasRunning { _error = "the modem stopped (the log says why)" }
        lock.unlock()
        changed()
    }

    public func engineLine(caller: Bool, dial: String, fd: Int32) {
        if let h = onLine { h(caller, dial, fd) } else { close(fd) }
    }

    public func engineSpeaker(_ level: Int) { onSpeaker?(level) }

    public func engineSound(_ pcm: UnsafeBufferPointer<Int16>) { onSound?(pcm) }
}
