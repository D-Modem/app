// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import DModemShared
import Foundation
import UIKit

/// The line to another phone with the app over Wi-Fi (the settings' line
/// .wifi): the shared Kotlin's PeerLine (PeerHost) while the modem runs,
/// the engine's line handed to it, offered on the network with Bonjour
/// (_dmodem._tcp, as the Android app's NSD) when it takes calls.
final class PeerLineController: NSObject, NetServiceDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var host: PeerHost?
    private var service: NetService?

    /// why it can't take calls (it can still call), or nil
    private(set) var error: String?

    /// the line for [settings], the modem's peer lines to it ([session].onLine)
    func start(_ settings: ModemSettings, session: ModemSession) {
        stop()
        let h = PeerHost(
            port: Int32(settings.peerPort),
            callTo: settings.peerAddress,
            pin: settings.peerPin,
            events: LogLink { e in Engine.shared.peerEvent(e) },
            log: LogLink { [weak session] l in session?.engineLog("line: " + l) },
            ringMs: 3000)
        let err = h.start(listen: settings.listen)
        lock.lock()
        host = h
        error = err
        lock.unlock()
        session.onLine = { caller, dial, fd in h.lineStarted(caller: caller, dial: dial, fd: fd) }
        if settings.listen && err == nil {
            DispatchQueue.main.async { self.advertise(port: Int(h.localPort)) }
        }
    }

    func stop() {
        lock.lock()
        let h = host
        host = nil
        lock.unlock()
        h?.stop()
        DispatchQueue.main.async {
            self.service?.stop()
            self.service = nil
        }
    }

    /// what the line is doing (nil while there's none)
    var stateText: String? {
        lock.lock(); defer { lock.unlock() }
        return host?.stateText
    }

    /// what went wrong last: a call that failed, or the calls it can't take
    var lastError: String? {
        lock.lock(); defer { lock.unlock() }
        return host?.lastError ?? error
    }

    private func advertise(port: Int) {
        let s = NetService(domain: "local.", type: PeerFinder.type, name: "D-Modem on \(UIDevice.current.name)", port: Int32(port))
        s.delegate = self
        s.publish()
        service = s
    }
}

/// Phones with the app taking calls on this network (Bonjour, as the
/// Android app's NSD finds them): each to [found] as (its name, host:port).
final class PeerFinder: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    static let type = "_dmodem._tcp."

    private let browser = NetServiceBrowser()
    private var resolving: [NetService] = []
    private let found: (String, String) -> Void

    init(found: @escaping (String, String) -> Void) {
        self.found = found
        super.init()
        browser.delegate = self
    }

    func start() { browser.searchForServices(ofType: Self.type, inDomain: "local.") }

    func stop() {
        browser.stop()
        resolving.forEach { $0.stop() }
        resolving.removeAll()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolving.append(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        // (an IPv4 address of it, else its host name; its port)
        let host = sender.addresses?.compactMap(Self.ipv4).first ?? sender.hostName
        if let host, sender.port > 0 { found(sender.name, "\(host):\(sender.port)") }
        resolving.removeAll { $0 === sender }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.removeAll { $0 === sender }
    }

    /// a sockaddr's IPv4 address, dotted; nil for another family
    private static func ipv4(_ data: Data) -> String? {
        data.withUnsafeBytes { raw -> String? in
            guard raw.count >= MemoryLayout<sockaddr_in>.size,
                  let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr_in.self),
                  sa.pointee.sin_family == sa_family_t(AF_INET) else { return nil }
            var addr = sa.pointee.sin_addr
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &addr, &buf, socklen_t(buf.count)) != nil else { return nil }
            return String(cString: buf)
        }
    }
}
