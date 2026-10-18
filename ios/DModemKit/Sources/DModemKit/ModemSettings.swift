// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation

/// How the modem runs (the Android app's ModemSettings, the lines iOS has:
/// a SIP account, or another phone with the app on the same Wi-Fi).
public struct ModemSettings: Codable, Equatable, Sendable {
    public enum Line: String, Codable, Sendable {
        case sip, wifi
    }

    /// the SIP account
    public var user = ""
    public var password = ""
    /// the registrar's or proxy's host[:port]
    public var domain = ""
    public var sipPort = 5060
    /// register and take calls (on Wi-Fi: take the other phone's)
    public var listen = false
    public var t38 = true
    /// the modem's country (empty: USA)
    public var country = ""
    /// AT commands sent each time it starts (SIP has no dial tone: ATX3)
    public var initCommands = "ATX3"
    public var logLevel = 0
    public var sipLogLevel = 1
    /// the modem's speaker on the phone's: the dialling and the handshake heard
    public var speaker = true
    public var line = Line.sip
    /// the other phone to call (host[:port]), and the PIN between the two
    public var peerAddress = ""
    public var peerPort = 5070
    public var peerPin = ""

    public init() {}

    /// the line another phone's, not SIP
    public var peer: Bool { line != .sip }

    /// enough to start: an account (or the line another phone's)
    public var complete: Bool { peer || (!user.isEmpty && !domain.isEmpty) }

    /// the commands sent as the modem starts (empty: none)
    public func startCommands() -> String {
        initCommands.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// the engine's configuration for these, with the DNS servers the network has
    public func engineConfig(dns: [String] = [], dataFile: String = "") -> EngineConfig {
        var c = EngineConfig()
        c.user = user
        c.password = password
        c.domain = domain
        c.sipPort = sipPort
        c.listen = listen
        c.t38 = t38
        c.dns = dns
        c.country = country
        c.dataFile = dataFile
        c.logLevel = logLevel
        c.sipLogLevel = sipLogLevel
        c.peer = peer
        return c
    }

    /// "user:password@host[:port]" (as dmodemd's SIP_LOGIN): its parts, nil if it isn't one
    public static func parseLogin(_ s: String) -> (user: String, password: String, domain: String)? {
        let t = s.trimmingCharacters(in: .whitespaces)
        // (the password may have an @ in it: the last one ends it)
        guard let at = t.range(of: "@", options: .backwards), let colon = t[..<at.lowerBound].firstIndex(of: ":") else { return nil }
        let user = String(t[..<colon])
        let password = String(t[t.index(after: colon)..<at.lowerBound])
        let domain = String(t[at.upperBound...])
        guard !user.isEmpty, !domain.isEmpty else { return nil }
        return (user, password, domain)
    }

    // (decoded leniently: a setting a newer version added is its default when missing)
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = ModemSettings()
        user = try c.decodeIfPresent(String.self, forKey: .user) ?? d.user
        password = try c.decodeIfPresent(String.self, forKey: .password) ?? d.password
        domain = try c.decodeIfPresent(String.self, forKey: .domain) ?? d.domain
        sipPort = try c.decodeIfPresent(Int.self, forKey: .sipPort) ?? d.sipPort
        listen = try c.decodeIfPresent(Bool.self, forKey: .listen) ?? d.listen
        t38 = try c.decodeIfPresent(Bool.self, forKey: .t38) ?? d.t38
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? d.country
        initCommands = try c.decodeIfPresent(String.self, forKey: .initCommands) ?? d.initCommands
        logLevel = try c.decodeIfPresent(Int.self, forKey: .logLevel) ?? d.logLevel
        sipLogLevel = try c.decodeIfPresent(Int.self, forKey: .sipLogLevel) ?? d.sipLogLevel
        speaker = try c.decodeIfPresent(Bool.self, forKey: .speaker) ?? d.speaker
        line = try c.decodeIfPresent(Line.self, forKey: .line) ?? d.line
        peerAddress = try c.decodeIfPresent(String.self, forKey: .peerAddress) ?? d.peerAddress
        peerPort = try c.decodeIfPresent(Int.self, forKey: .peerPort) ?? d.peerPort
        peerPin = try c.decodeIfPresent(String.self, forKey: .peerPin) ?? d.peerPin
    }
}

/// The settings kept (UserDefaults, as JSON). The password stays there
/// for now; the Keychain is the app's next step.
public final class SettingsStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "modemSettings") {
        self.defaults = defaults
        self.key = key
    }

    public var settings: ModemSettings {
        get {
            guard let d = defaults.data(forKey: key), let s = try? JSONDecoder().decode(ModemSettings.self, from: d) else {
                return ModemSettings()
            }
            return s
        }
        set {
            if let d = try? JSONEncoder().encode(newValue) { defaults.set(d, forKey: key) }
        }
    }
}
