// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemShared
import Foundation
import SwiftUI

/// The terminal on the modem: the shared Kotlin's emulator
/// (TerminalBridge) fed what the modem says, its keys and replies to the
/// modem ([send]). Octets cross to Kotlin as Latin-1 strings, one
/// character each.
@MainActor
final class TerminalModel: ObservableObject {
    let bridge = TerminalBridge(cols: 80, rows: 24)
    /// the screen changed: redraw
    @Published private(set) var revision: Int64 = 0
    /// the next key with Ctrl (the extra keys' sticky Ctrl)
    @Published var ctrl = false
    /// the next key with Alt (the key bar's sticky Alt)
    @Published var alt = false
    /// the status line below the screen (a VT320's, DECSSDT): "" for none
    @Published private(set) var statusText = ""

    /// to the modem
    var send: ((Data) -> Void)?

    /// the emulator set up for BBSes: CP437, bold as bright, the VGA colours
    init() {
        // (BBSes: CP437, bold as bright; the VGA colours)
        bridge.setLook(themeId: "vga", boldBright: true, iceColors: false, charset: "CP437")
    }

    /// the kind of terminal (a profile's id, as Settings keeps it under "terminalProfile")
    func setProfile(_ id: String) {
        bridge.profile = id
        glyphCache.removeAll()
        revision = bridge.revision
    }

    /// a bitmap glyph: its rows, top first, bit width - 1 the leftmost pixel
    struct Glyph {
        let rows: [Int]
        let width: Int
    }

    /// the profile's glyphs, as they're asked for
    private var glyphCache: [UInt32: Glyph?] = [:]

    /// the bitmap glyph for [cp] (the profile's, 8 wide and 8 or 16 high, or a soft character the host loaded); nil: drawn with the font
    func glyph(_ cp: UInt32) -> Glyph? {
        // (soft characters can be loaded again: never kept)
        let soft = cp >= 0xF0000
        if !soft, let g = glyphCache[cp] { return g }
        let c = Int32(bitPattern: cp)
        let h = Int(bridge.glyphHeightOf(cp: c))
        let g: Glyph? = h == 0 ? nil : Glyph(rows: (0..<h).map { Int(bridge.glyphRow(cp: c, row: Int32($0))) },
                                             width: Int(bridge.glyphWidthOf(cp: c)))
        if !soft { glyphCache[cp] = g }
        return g
    }

    var cols: Int { Int(bridge.cols) }
    var rows: Int { Int(bridge.rows) }

    /// ANSI music's notes, played as they come
    private let music = MusicPlayer()
    /// what the modem said that's still to be shown, at an emulated line speed
    private var paced = Data()
    /// the pacing under way
    private var pacing: Task<Void, Never>?

    /// what the modem said, onto the screen (at the line speed the remote asked for, CTerm's CSI * r, if it did); the terminal's answers back
    func feed(_ data: Data) {
        if pacing != nil || bridge.emulatedBps > 0 {
            paced.append(data)
            if pacing == nil { pacing = Task { await pace() } }
            return
        }
        show(data)
    }

    /// the paced octets onto the screen a few at a time, every 20 ms
    private func pace() async {
        while !paced.isEmpty {
            let bps = Int(bridge.emulatedBps)
            let n = bps > 0 ? min(paced.count, max(1, bps / 10 / 50)) : paced.count
            show(paced.prefix(n))
            paced.removeFirst(n)
            if bps > 0 { try? await Task.sleep(nanoseconds: 20_000_000) }
        }
        pacing = nil
    }

    /// octets onto the screen now, the answers and music they bring dealt with
    private func show(_ data: Data) {
        guard let s = String(data: data, encoding: .isoLatin1) else { return }
        bridge.feed(latin1: s)
        let replies = bridge.takeReplies()
        if !replies.isEmpty { out(replies) }
        let notes = bridge.takeMusic()
        if !notes.isEmpty { music.play(notes) }
        let status = bridge.statusText
        if status != statusText { statusText = status }
        revision = bridge.revision
    }

    /// text typed (the keyboard's), Ctrl and Alt (a Commodore's C=) if the sticky ones are down
    func typed(_ text: String) {
        out(bridge.typed(text: text, ctrl: ctrl, alt: alt))
        ctrl = false
        alt = false
    }

    /// a key that isn't a character: UP, DOWN, LEFT, RIGHT, ESCAPE, TAB, BACKSPACE, ENTER, F1 ...
    func key(_ name: String) {
        out(bridge.key(name: name, shift: false, alt: false, ctrl: ctrl))
        ctrl = false
    }

    /// the key bar's key at ([row], [col]) of [page] pressed, Ctrl and Alt if the sticky ones are down
    func barKey(page: Int, row: Int, col: Int) {
        out(bridge.keyBarPress(page: Int32(page), row: Int32(row), col: Int32(col), ctrl: ctrl, alt: alt))
        ctrl = false
        alt = false
    }

    /// the screen to [cols] by [rows] (the view's size)
    func resize(cols: Int, rows: Int) {
        guard cols > 0, rows > 0, cols != self.cols || rows != self.rows else { return }
        bridge.resize(cols: Int32(cols), rows: Int32(rows))
        revision = bridge.revision
    }

    /// a row's runs
    func runs(_ row: Int) -> [StyledRun] { bridge.runs(row: Int32(row)) }

    /// the emulator's Latin-1 output as octets to the modem (nothing if there's none)
    private func out(_ latin1: String) {
        guard !latin1.isEmpty, let d = latin1.data(using: .isoLatin1) else { return }
        send?(d)
    }
}

extension Color {
    /// an ARGB colour as Kotlin has it
    init(argb: Int32) {
        let u = UInt32(bitPattern: argb)
        self.init(
            .sRGB,
            red: Double((u >> 16) & 0xff) / 255,
            green: Double((u >> 8) & 0xff) / 255,
            blue: Double(u & 0xff) / 255,
            opacity: Double((u >> 24) & 0xff) / 255)
    }
}
