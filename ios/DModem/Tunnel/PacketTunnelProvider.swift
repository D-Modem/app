// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation
import NetworkExtension

/// The dial-up link as the phone's VPN: its address and DNS (from the app,
/// which has the modem and PPP), every IPv4 packet to the app as a UDP
/// datagram on the loopback and the app's back to the system. (The
/// extension runs apart from the app; the loopback isn't routed through
/// the tunnel.)
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var fd: Int32 = -1
    private var app = sockaddr_in()
    private let lock = NSLock()
    private var running = false

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        guard let o = options, let address = o["address"] as? String, let port = (o["port"] as? NSNumber)?.uint16Value else {
            completionHandler(NSError(domain: "DModemTunnel", code: 1, userInfo: [NSLocalizedDescriptionKey: "start it from D-Modem's Internet screen"]))
            return
        }
        let dns = ((o["dns"] as? String) ?? "").split(separator: ",").map(String.init)
        let mtu = (o["mtu"] as? NSNumber)?.intValue ?? 1500

        let s = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let v4 = NEIPv4Settings(addresses: [address], subnetMasks: ["255.255.255.255"])
        v4.includedRoutes = [NEIPv4Route.default()]
        s.ipv4Settings = v4
        if !dns.isEmpty { s.dnsSettings = NEDNSSettings(servers: dns) }
        s.mtu = NSNumber(value: mtu)

        app.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        app.sin_family = sa_family_t(AF_INET)
        app.sin_port = port.bigEndian
        app.sin_addr.s_addr = inet_addr("127.0.0.1")
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else {
            completionHandler(NSError(domain: "DModemTunnel", code: 2, userInfo: [NSLocalizedDescriptionKey: "no socket"]))
            return
        }
        setTunnelNetworkSettings(s) { [weak self] err in
            guard let self, err == nil else { completionHandler(err); return }
            self.lock.lock(); self.running = true; self.lock.unlock()
            // (a hello: the app learns where we are)
            self.sendToApp(Data([0]))
            self.readFromApp()
            self.readPackets()
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        lock.lock()
        running = false
        let f = fd
        fd = -1
        lock.unlock()
        if f >= 0 { shutdown(f, SHUT_RDWR); close(f) }
        completionHandler()
    }

    private var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return running }

    private func sendToApp(_ d: Data) {
        var to = app
        d.withUnsafeBytes { p in
            withUnsafePointer(to: &to) { a in
                a.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(fd, p.baseAddress, d.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    /// the system's packets to the app
    private func readPackets() {
        packetFlow.readPackets { [weak self] packets, protocols in
            guard let self, self.isRunning else { return }
            for (p, proto) in zip(packets, protocols) where proto.int32Value == AF_INET {
                self.sendToApp(p)
            }
            self.readPackets()
        }
    }

    /// the app's packets to the system
    private func readFromApp() {
        let f = fd
        Thread.detachNewThread { [weak self] in
            var buf = [UInt8](repeating: 0, count: 65536)
            while let self, self.isRunning {
                let n = buf.withUnsafeMutableBytes { recv(f, $0.baseAddress, $0.count, 0) }
                if n <= 0 { break }
                // (anything shorter than an IPv4 header: nothing to pass on)
                if n >= 20 { self.packetFlow.writePackets([Data(buf[0..<n])], withProtocols: [NSNumber(value: AF_INET)]) }
            }
        }
    }
}
