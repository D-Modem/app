// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import Foundation
#if canImport(Glibc)
import Glibc
#endif

/// The modem's DTE: what it says (its results, the remote's data) read on
/// a thread of its own to [onData]; what we send it (AT commands, data)
/// written. Closing it stops the modem.
public final class DTE: @unchecked Sendable {
    private let fd: Int32
    private let lock = NSLock()
    private var closed = false
    private var reader: Thread?
    private var handler: ((Data) -> Void)?

    /// the DTE on the modem's end [fd] (closed with it)
    init(fd: Int32) {
        self.fd = fd
    }

    /// the modem's octets as they come (on the reader's thread), until it ends ([onEnd])
    public func read(onData: @escaping (Data) -> Void, onEnd: @escaping () -> Void = {}) {
        lock.lock()
        handler = onData
        let start = reader == nil
        lock.unlock()
        guard start else { return }
        let t = Thread { [weak self] in
            var buf = [UInt8](repeating: 0, count: 4096)
            while let self {
                let n = buf.withUnsafeMutableBytes { Foundation.read(self.fd, $0.baseAddress, $0.count) }
                if n < 0 && errno == EINTR { continue }
                if n <= 0 { break }
                let data = Data(buf[0..<n])
                self.lock.lock(); let h = self.handler; self.lock.unlock()
                h?(data)
            }
            onEnd()
        }
        t.name = "dte"
        lock.lock(); reader = t; lock.unlock()
        t.start()
    }

    /// [data] to the modem, all of it; false once it's gone
    @discardableResult
    public func write(_ data: Data) -> Bool {
        lock.lock(); let gone = closed; lock.unlock()
        if gone { return false }
        return data.withUnsafeBytes { raw -> Bool in
            var p = raw.baseAddress!
            var left = raw.count
            while left > 0 {
                let w = Foundation.write(fd, p, left)
                if w < 0 && errno == EINTR { continue }
                if w <= 0 { return false }
                p += w
                left -= w
            }
            return true
        }
    }

    /// [text] to the modem as UTF-8; false once it's gone
    @discardableResult
    public func write(_ text: String) -> Bool { write(Data(text.utf8)) }

    /// an AT command (its CR added)
    @discardableResult
    public func command(_ cmd: String) -> Bool { write(cmd + "\r") }

    /// the DTE closed: the modem stops
    public func close() {
        lock.lock()
        let was = closed
        closed = true
        lock.unlock()
        if !was {
            // (the reader's read ends: the other end gone)
            shutdown(fd, Int32(SHUT_RDWR))
            Foundation.close(fd)
        }
    }

    /// closed with the last reference (the modem stops)
    deinit { close() }
}
