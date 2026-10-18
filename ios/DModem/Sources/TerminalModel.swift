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

    /// to the modem
    var send: ((Data) -> Void)?

    init() {
        // (BBSes: CP437, bold as bright; the VGA colours)
        bridge.setLook(themeId: "vga", boldBright: true, iceColors: false, charset: "CP437")
    }

    var cols: Int { Int(bridge.cols) }
    var rows: Int { Int(bridge.rows) }

    /// what the modem said, onto the screen; the terminal's answers back
    func feed(_ data: Data) {
        guard let s = String(data: data, encoding: .isoLatin1) else { return }
        bridge.feed(latin1: s)
        let replies = bridge.takeReplies()
        if !replies.isEmpty { out(replies) }
        revision = bridge.revision
    }

    /// text typed (the keyboard's), Ctrl if the sticky one is down
    func typed(_ text: String) {
        out(bridge.typed(text: text, ctrl: ctrl, alt: false))
        ctrl = false
    }

    /// a key that isn't a character: UP, DOWN, LEFT, RIGHT, ESCAPE, TAB, BACKSPACE, ENTER, F1 ...
    func key(_ name: String) {
        out(bridge.key(name: name, shift: false, alt: false, ctrl: ctrl))
        ctrl = false
    }

    /// the screen to [cols] by [rows] (the view's size)
    func resize(cols: Int, rows: Int) {
        guard cols > 0, rows > 0, cols != self.cols || rows != self.rows else { return }
        bridge.resize(cols: Int32(cols), rows: Int32(rows))
        revision = bridge.revision
    }

    /// a row's runs
    func runs(_ row: Int) -> [StyledRun] { bridge.runs(row: Int32(row)) }

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
