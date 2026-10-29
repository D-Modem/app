// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import Foundation

/// How the modem runs (the Android app's ModemSettings, the lines iOS has:
/// a SIP account, or another phone with the app on the same Wi-Fi).
public struct ModemSettings: Codable, Equatable, Sendable {
    public enum Line: String, Codable, Sendable {
        /// a SIP account; another phone with the app on Wi-Fi; nothing at all (the modem on its own)
        case sip, wifi, none
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

    /// fax: the identity sent (CSI/TSI), error correction, the fastest rate, fine resolution,
    /// ringing calls answered as faxes
    public var faxIdent = ""
    public var faxEcm = true
    public var faxMaxBps = 14400
    public var faxFine = true
    public var faxAutoAnswer = false

    /// dial-up internet: the ISP's number, login, its login script (chat, if any), the modem's commands first
    public var ispNumber = ""
    public var ispUser = ""
    public var ispPassword = ""
    public var ispScript = ""
    public var ispInit = ""

    /// the defaults
    public init() {}

    /// the settings' secrets, kept apart from the rest (the Keychain), by name
    public static let secretKeys: [(String, WritableKeyPath<ModemSettings, String>)] = [
        ("sip-password", \.password), ("peer-pin", \.peerPin), ("isp-password", \.ispPassword),
    ]

    /// the line another phone's (or none), not SIP
    public var peer: Bool { line != .sip }

    /// no line: the modem on its own (AT commands, the terminal); a dial gets NO DIALTONE
    public var noLine: Bool { line == .none }

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
        faxIdent = try c.decodeIfPresent(String.self, forKey: .faxIdent) ?? d.faxIdent
        faxEcm = try c.decodeIfPresent(Bool.self, forKey: .faxEcm) ?? d.faxEcm
        faxMaxBps = try c.decodeIfPresent(Int.self, forKey: .faxMaxBps) ?? d.faxMaxBps
        faxFine = try c.decodeIfPresent(Bool.self, forKey: .faxFine) ?? d.faxFine
        faxAutoAnswer = try c.decodeIfPresent(Bool.self, forKey: .faxAutoAnswer) ?? d.faxAutoAnswer
        ispNumber = try c.decodeIfPresent(String.self, forKey: .ispNumber) ?? d.ispNumber
        ispUser = try c.decodeIfPresent(String.self, forKey: .ispUser) ?? d.ispUser
        ispPassword = try c.decodeIfPresent(String.self, forKey: .ispPassword) ?? d.ispPassword
        ispScript = try c.decodeIfPresent(String.self, forKey: .ispScript) ?? d.ispScript
        ispInit = try c.decodeIfPresent(String.self, forKey: .ispInit) ?? d.ispInit
    }
}

/// The settings kept: in UserDefaults as JSON, but for their secrets (the
/// SIP password, the PIN, the ISP's password), which go to [secrets] (the
/// Keychain). Secrets an older version left in the JSON are moved there
/// the first time they're read.
public final class SettingsStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let secrets: SecretStore

    /// settings kept under [key] in [defaults], their secrets in [secrets]
    public init(defaults: UserDefaults = .standard, key: String = "modemSettings", secrets: SecretStore = defaultSecrets()) {
        self.defaults = defaults
        self.key = key
        self.secrets = secrets
    }

    public var settings: ModemSettings {
        get {
            guard let d = defaults.data(forKey: key), var s = try? JSONDecoder().decode(ModemSettings.self, from: d) else {
                return ModemSettings()
            }
            var moved = false
            for (name, path) in ModemSettings.secretKeys {
                if !s[keyPath: path].isEmpty {
                    // (an older version's, in the JSON: to the secrets, out of the JSON)
                    secrets.set(name, s[keyPath: path])
                    moved = true
                } else {
                    s[keyPath: path] = secrets.get(name) ?? ""
                }
            }
            if moved { save(s) }
            return s
        }
        set {
            for (name, path) in ModemSettings.secretKeys { secrets.set(name, newValue[keyPath: path]) }
            save(newValue)
        }
    }

    /// the JSON, without the secrets
    private func save(_ s: ModemSettings) {
        var plain = s
        for (_, path) in ModemSettings.secretKeys { plain[keyPath: path] = "" }
        if let d = try? JSONEncoder().encode(plain) { defaults.set(d, forKey: key) }
    }
}
