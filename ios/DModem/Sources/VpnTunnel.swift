// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemShared
import Foundation
import NetworkExtension

/// The phone's traffic through the dial-up link: the app's packet tunnel
/// (the DModemTunnel extension) started with the link's address and DNS,
/// told where the shared Kotlin's UdpPacketTunnel listens for its packets.
/// The first time, iOS asks the user to allow the VPN configuration.
final class VpnTunnel: NSObject, TunnelStarter, @unchecked Sendable {
    static let extensionId = "com.neodesktop.dmodem.tunnel"
    private var manager: NETunnelProviderManager?

    /// (on the link's thread, never the main one: it waits for the system)
    func start(address: String, dns: String, mtu: Int32, port: Int32) -> Bool {
        let done = DispatchSemaphore(value: 0)
        let result = Started()
        Task {
            do {
                let m = try await Self.manager()
                try m.connection.startVPNTunnel(options: [
                    "address": address as NSString, "dns": dns as NSString,
                    "mtu": NSNumber(value: mtu), "port": NSNumber(value: port),
                ])
                self.manager = m
                result.ok = true
            } catch {
                NSLog("vpn: \(error)")
            }
            done.signal()
        }
        done.wait()
        return result.ok
    }

    /// (whether it started, set by the task the start waits for)
    private final class Started: @unchecked Sendable { var ok = false }

    /// the packet tunnel stopped (if it was started)
    func stop() {
        manager?.connection.stopVPNTunnel()
        manager = nil
    }

    /// the app's VPN configuration: found, or made (and saved, which the user allows)
    private static func manager() async throws -> NETunnelProviderManager {
        let all = try await NETunnelProviderManager.loadAllFromPreferences()
        let m = all.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == extensionId }
            ?? NETunnelProviderManager()
        let p = NETunnelProviderProtocol()
        p.providerBundleIdentifier = extensionId
        p.serverAddress = "Dial-up"
        m.protocolConfiguration = p
        m.localizedDescription = "D-Modem dial-up"
        m.isEnabled = true
        try await m.saveToPreferences()
        // (loaded again after a save, or the start fails)
        try await m.loadFromPreferences()
        return m
    }
}
