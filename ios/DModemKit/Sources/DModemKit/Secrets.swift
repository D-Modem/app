// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation
#if canImport(Security)
import Security
#endif

/// Where the settings' secrets are kept (the SIP password, the PIN, the
/// ISP's password): the Keychain on Apple's systems, memory elsewhere (the
/// tests on Linux).
public protocol SecretStore: AnyObject, Sendable {
    /// the secret named [key], nil when there's none
    func get(_ key: String) -> String?
    /// [value] kept under [key] (empty: removed)
    func set(_ key: String, _ value: String)
}

/// secrets in memory, for as long as the process runs
public final class MemorySecrets: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]

    public init() {}

    public func get(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return values[key]
    }

    public func set(_ key: String, _ value: String) {
        lock.lock(); defer { lock.unlock() }
        values[key] = value.isEmpty ? nil : value
    }
}

#if canImport(Security)
/// secrets in the Keychain: generic passwords under [service], on this
/// device only, readable once it's been unlocked after a restart (so a
/// modem started in the background still can)
public final class KeychainSecrets: SecretStore, @unchecked Sendable {
    private let service: String

    public init(service: String = "com.neodesktop.dmodem") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    public func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    public func set(_ key: String, _ value: String) {
        let q = query(key)
        if value.isEmpty {
            SecItemDelete(q as CFDictionary)
            return
        }
        let attrs: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(q as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            SecItemAdd(q.merging(attrs) { $1 } as CFDictionary, nil)
        }
    }
}
#endif

/// the store the app has: the Keychain where there is one
public func defaultSecrets() -> SecretStore {
    #if canImport(Security)
    return KeychainSecrets()
    #else
    return MemorySecrets()
    #endif
}
