// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation

/// The phone's network's DNS servers (SystemDNS.c), for the engine's SIP.
enum SystemDNS {
    static func servers() -> [String] {
        var buf = [CChar](repeating: 0, count: 512)
        guard dmodem_dns_servers(&buf, Int32(buf.count)) > 0 else { return [] }
        return String(cString: buf).split(separator: ",").map(String.init)
    }
}
