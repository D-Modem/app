// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemEngine
import Foundation

/// the call's state, as the engine reports it
public enum Link: Int, Sendable {
    case idle = 0, connecting, online, hangingUp
}

public struct ModemStatus: Equatable, Sendable {
    public var link: Link = .idle
    public var offHook = false
    public var txRate = 0
    public var rxRate = 0

    /// a status as given (on hook and idle by default)
    public init(link: Link = .idle, offHook: Bool = false, txRate: Int = 0, rxRate: Int = 0) {
        self.link = link
        self.offHook = offHook
        self.txRate = txRate
        self.rxRate = rxRate
    }
}

/// what the modem starts with (dmodem_config)
public struct EngineConfig: Sendable {
    public var user = "", password = "", domain = ""
    public var sipPort = 5060
    /// register and take calls (or, on a peer line, take the other phone's)
    public var listen = false
    public var t38 = true
    /// DNS servers for SRV and NAPTR (iOS has no resolv.conf the engine can read)
    public var dns: [String] = []
    public var country = ""
    /// where the modem keeps what it learns of the line
    public var dataFile = ""
    public var logLevel = 0, sipLogLevel = 1
    /// the line another phone's through the app, not SIP; its lead silence (ms)
    public var peer = false
    public var peerLeadMs = 100

    /// the defaults: SIP on 5060, not listening, T.38 on
    public init() {}
}

/// what the engine tells, from its threads
public protocol EngineListener: AnyObject {
    /// a line of the engine's log (its newline gone)
    func engineLog(_ line: String)
    /// the call's state changed: link, hook, rates
    func engineStatus(_ status: ModemStatus)
    /// the modem stopped: 0 asked for (or the DTE closed), -1 an error
    func engineExited(_ result: Int)
    /// a peer line's modem off hook: dialling [dial] (caller) or answering; [fd] its samples, ours to close
    func engineLine(caller: Bool, dial: String, fd: Int32)
    /// the speaker (ATM, ATL): 0 off, 1...3
    func engineSpeaker(_ level: Int)
    /// the line's sound while the speaker is on (16 bit, 8000 Hz)
    func engineSound(_ pcm: UnsafeBufferPointer<Int16>)
}

public extension EngineListener {
    /// (no peer line taken: its samples closed)
    func engineLine(caller: Bool, dial: String, fd: Int32) { close(fd) }
    /// (the speaker ignored)
    func engineSpeaker(_ level: Int) {}
    /// (the sound ignored)
    func engineSound(_ pcm: UnsafeBufferPointer<Int16>) {}
}

public enum EngineError: Error, Equatable {
    /// it runs already, or couldn't start (the log says why)
    case notStarted
}

/// The modem (one in the process): dmodem_engine.h from Swift. Its
/// callbacks come on the engine's threads; [listener] gets them there.
public final class Engine: @unchecked Sendable {
    public static let shared = Engine()

    private let lock = NSLock()
    private weak var _listener: EngineListener?

    var listener: EngineListener? {
        lock.lock(); defer { lock.unlock() }
        return _listener
    }

    /// the engine's callbacks set once, each to the listener of the moment
    private init() {
        var cb = dmodem_callbacks()
        // (one engine, one Engine: the callbacks find it through this, not a context)
        cb.ctx = nil
        cb.log = { _, line in
            guard let line else { return }
            Engine.shared.listener?.engineLog(String(cString: line).trimmingCharacters(in: .newlines))
        }
        cb.status = { _, link, offhook, tx, rx in
            Engine.shared.listener?.engineStatus(
                ModemStatus(link: Link(rawValue: Int(link)) ?? .idle, offHook: offhook != 0, txRate: Int(tx), rxRate: Int(rx)))
        }
        cb.exited = { _, result in Engine.shared.listener?.engineExited(Int(result)) }
        cb.line = { _, caller, dial, fd in
            guard let l = Engine.shared.listener else { close(fd); return }
            l.engineLine(caller: caller != 0, dial: dial.map { String(cString: $0) } ?? "", fd: fd)
        }
        cb.speaker = { _, level in Engine.shared.listener?.engineSpeaker(Int(level)) }
        cb.sound = { _, pcm, n in
            guard let pcm, n > 0 else { return }
            Engine.shared.listener?.engineSound(UnsafeBufferPointer(start: pcm, count: Int(n)))
        }
        dmodem_set_callbacks(&cb)
    }

    /// the modem started with [config], its callbacks to [listener]: its DTE
    public func start(_ config: EngineConfig, listener: EngineListener) throws -> DTE {
        lock.lock(); _listener = listener; lock.unlock()
        var c = dmodem_config()
        dmodem_config_init(&c)
        let dns = config.dns.joined(separator: ",")
        let fd: Int32 = config.user.withCString { user in
            config.password.withCString { password in
                config.domain.withCString { domain in
                    dns.withCString { dnsC in
                        config.country.withCString { country in
                            config.dataFile.withCString { dataFile in
                                c.user = user
                                c.password = password
                                c.domain = domain
                                c.sip_port = Int32(config.sipPort)
                                c.listen = config.listen ? 1 : 0
                                c.t38 = config.t38 ? 1 : 0
                                c.dns = dns.isEmpty ? nil : dnsC
                                c.country = country
                                c.data_file = config.dataFile.isEmpty ? nil : dataFile
                                c.log_level = Int32(config.logLevel)
                                c.sip_log_level = Int32(config.sipLogLevel)
                                c.peer = config.peer ? 1 : 0
                                c.peer_lead_ms = Int32(config.peerLeadMs)
                                return dmodem_start(&c)
                            }
                        }
                    }
                }
            }
        }
        guard fd >= 0 else { throw EngineError.notStarted }
        return DTE(fd: fd)
    }

    /// the modem stopped (its call hung up); waits for it
    public func stop() { dmodem_stop() }

    public var running: Bool { dmodem_running() != 0 }

    /// a peer line's event: RING, CANCEL, ANSWERED, FAILED <SIP status>, DROPPED
    public func peerEvent(_ line: String) { dmodem_peer_event(line) }
}
