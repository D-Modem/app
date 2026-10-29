// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
import DModemShared
import SwiftUI
import UIKit

/// The terminal: the screen drawn run by run in its cells, the keyboard
/// on a tap (KeyInput), the keys a phone's keyboard lacks below it.
struct TerminalScreen: View {
    @ObservedObject var model: TerminalModel
    @State private var focused = true
    @AppStorage("terminalFontSize") private var fontSize: Double = 12

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                let cell = CellMetrics(fontSize: fontSize)
                ScreenCanvas(model: model, revision: model.revision, cell: cell)
                    .background(Color(argb: model.bridge.background))
                    .onAppear { fit(geo.size, cell) }
                    .onChange(of: geo.size) { fit($0, cell) }
                    .onChange(of: fontSize) { _ in fit(geo.size, CellMetrics(fontSize: fontSize)) }
                    .overlay(KeyInput(model: model, focused: $focused).frame(width: 1, height: 1).opacity(0.01), alignment: .topLeading)
                    .contentShape(Rectangle())
                    .onTapGesture { focused = true }
                    .gesture(MagnificationGesture().onEnded { s in fontSize = min(24, max(7, fontSize * Double(s))) })
            }
            ExtraKeys(model: model)
        }
        .navigationTitle(model.bridge.title.isEmpty ? "Terminal" : model.bridge.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// as many cells as fit
    private func fit(_ size: CGSize, _ cell: CellMetrics) {
        model.resize(cols: max(20, Int(size.width / cell.width)), rows: max(5, Int(size.height / cell.height)))
    }
}

/// a cell's size in the terminal's font
struct CellMetrics {
    let font: UIFont
    let width: CGFloat
    let height: CGFloat

    /// the monospaced system font at [fontSize], and its "M"'s width and line height rounded up
    init(fontSize: Double) {
        font = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        width = ceil(("M" as NSString).size(withAttributes: [.font: font]).width)
        height = ceil(font.lineHeight)
    }
}

/// the screen: each row's runs, their backgrounds, their text, the cursor
private struct ScreenCanvas: View {
    @ObservedObject var model: TerminalModel
    /// (read here so the canvas redraws with each change)
    let revision: Int64
    let cell: CellMetrics

    var body: some View {
        Canvas { ctx, _ in
            let regular = Font(cell.font as CTFont)
            let bold = Font(UIFont.monospacedSystemFont(ofSize: cell.font.pointSize, weight: .bold) as CTFont)
            for row in 0..<model.rows {
                let y = CGFloat(row) * cell.height
                for run in model.runs(row) {
                    let x = CGFloat(run.col) * cell.width
                    let w = CGFloat(run.cells) * cell.width
                    ctx.fill(Path(CGRect(x: x, y: y, width: w, height: cell.height)), with: .color(Color(argb: run.bg)))
                    if run.text.trimmingCharacters(in: .whitespaces).isEmpty { continue }
                    var t = Text(run.text).font(run.bold ? bold : regular).foregroundColor(Color(argb: run.fg))
                    if run.italic { t = t.italic() }
                    if run.underline { t = t.underline() }
                    if run.strike { t = t.strikethrough() }
                    ctx.draw(t, at: CGPoint(x: x, y: y), anchor: .topLeading)
                }
            }
            if model.bridge.cursorVisible {
                let r = CGRect(x: CGFloat(model.bridge.cursorX) * cell.width, y: CGFloat(model.bridge.cursorY) * cell.height,
                               width: cell.width, height: cell.height)
                ctx.fill(Path(r), with: .color(Color(argb: model.bridge.cursorColor).opacity(0.6)))
            }
        }
    }
}

/// the keys a phone's keyboard lacks: Esc, a sticky Ctrl, Tab, the arrows
private struct ExtraKeys: View {
    @ObservedObject var model: TerminalModel

    var body: some View {
        HStack(spacing: 6) {
            key("Esc") { model.key("ESCAPE") }
            Button(action: { model.ctrl.toggle() }) {
                Text("Ctrl").frame(minWidth: 40, minHeight: 32)
            }
            .buttonStyle(.bordered)
            .tint(model.ctrl ? .accentColor : .secondary)
            key("Tab") { model.key("TAB") }
            key("←") { model.key("LEFT") }
            key("↓") { model.key("DOWN") }
            key("↑") { model.key("UP") }
            key("→") { model.key("RIGHT") }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(.bar)
    }

    /// a bordered key showing [label] that runs [action]
    private func key(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(label).frame(minWidth: 32, minHeight: 32) }
            .buttonStyle(.bordered)
    }
}

/// a view the keyboard types into: its text and keys to the terminal
/// (UIKeyInput), the hardware keyboard's arrows and Esc too
struct KeyInput: UIViewRepresentable {
    let model: TerminalModel
    @Binding var focused: Bool

    /// the view, typing into [model]
    func makeUIView(context: Context) -> KeyInputView {
        let v = KeyInputView()
        v.model = model
        return v
    }

    /// the view's model kept, and the keyboard brought up once it's [focused]
    func updateUIView(_ v: KeyInputView, context: Context) {
        v.model = model
        if focused && !v.isFirstResponder {
            DispatchQueue.main.async { v.becomeFirstResponder() }
        }
    }
}

/// (a UIView: on the main actor, as the terminal's model is)
final class KeyInputView: UIView, UIKeyInput {
    weak var model: TerminalModel?

    override var canBecomeFirstResponder: Bool { true }
    var hasText: Bool { true }

    // (a terminal's keyboard: nothing corrected, capitalised or made smart)
    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var smartQuotesType: UITextSmartQuotesType = .no
    var smartDashesType: UITextSmartDashesType = .no
    var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    var keyboardType: UIKeyboardType = .asciiCapable

    /// the keyboard's text to the terminal
    func insertText(_ text: String) {
        model?.typed(text)
    }

    /// the keyboard's delete as the terminal's BACKSPACE
    func deleteBackward() {
        model?.key("BACKSPACE")
    }

    override var keyCommands: [UIKeyCommand]? {
        [
            (UIKeyCommand.inputUpArrow, "UP"), (UIKeyCommand.inputDownArrow, "DOWN"),
            (UIKeyCommand.inputLeftArrow, "LEFT"), (UIKeyCommand.inputRightArrow, "RIGHT"),
            (UIKeyCommand.inputEscape, "ESCAPE"), ("\t", "TAB"),
        ].map { input, name in
            let c = UIKeyCommand(input: input, modifierFlags: [], action: #selector(keyCommand(_:)))
            c.wantsPriorityOverSystemBehavior = true
            c.title = name
            return c
        }
    }

    /// a hardware key (named in its command's title) to the terminal
    @objc private func keyCommand(_ c: UIKeyCommand) {
        guard let name = c.title.isEmpty ? nil : c.title else { return }
        model?.key(name)
    }
}
