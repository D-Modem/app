// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemShared
import SwiftUI
import UIKit

/// The terminal: the screen drawn run by run in its cells, the keyboard
/// on a tap (KeyInput), the keys a phone's keyboard lacks below it.
struct TerminalScreen: View {
    @ObservedObject var model: TerminalModel
    @State private var focused = true
    @AppStorage("terminalFontSize") private var fontSize: Double = 12
    @AppStorage("terminalProfile") private var profile = "xterm"

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                let cell = metrics(geo.size)
                ScreenCanvas(model: model, revision: model.revision, cell: cell)
                    .background(Color(argb: model.bridge.background))
                    .onAppear { fit(geo.size, cell) }
                    .onChange(of: geo.size) { fit($0, metrics($0)) }
                    .onChange(of: fontSize) { _ in fit(geo.size, metrics(geo.size)) }
                    // (the remote's DECCOLM: 80 or 132 columns fitted to the width)
                    .onChange(of: model.bridge.requestedColumns) { _ in fit(geo.size, metrics(geo.size)) }
                    // (a machine's own screen: its rows at most, square cells for its glyphs)
                    .onChange(of: model.bridge.requestedRows) { _ in fit(geo.size, metrics(geo.size)) }
                    .onChange(of: model.bridge.glyphHeight) { _ in fit(geo.size, metrics(geo.size)) }
                    .overlay(KeyInput(model: model, focused: $focused).frame(width: 1, height: 1).opacity(0.01), alignment: .topLeading)
                    .contentShape(Rectangle())
                    .onTapGesture { focused = true }
                    .gesture(MagnificationGesture().onEnded { s in fontSize = min(24, max(7, fontSize * Double(s))) })
            }
            // a VT320's status line, below the screen (the screen a line shorter)
            if !model.statusText.isEmpty {
                Text(model.statusText)
                    .font(.system(size: 12, design: .monospaced))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
                    .background(Color(.secondarySystemBackground))
            }
            ExtraKeys(model: model)
        }
        .onAppear { model.setProfile(profile) }
        .onChange(of: profile) { model.setProfile($0) }
        .navigationTitle(model.bridge.title.isEmpty ? "Terminal" : model.bridge.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// the cells: as many as the remote or the machine asked for (DECCOLM, a Commodore's 40) across [size], else at the font size; square for bitmap glyphs
    private func metrics(_ size: CGSize) -> CellMetrics {
        let asked = Int(model.bridge.requestedColumns)
        let m = asked > 0 && size.width > 0 ? CellMetrics(columns: asked, across: size.width) : CellMetrics(fontSize: fontSize)
        return model.bridge.bitmapGlyphs ? m.shaped(rows: Int(model.bridge.glyphHeight)) : m
    }

    /// as many cells as fit (no more rows than the machine's screen had)
    private func fit(_ size: CGSize, _ cell: CellMetrics) {
        let asked = Int(model.bridge.requestedColumns)
        let fitRows = max(5, Int(size.height / cell.height))
        let maxRows = Int(model.bridge.requestedRows)
        model.resize(cols: asked > 0 ? asked : max(20, Int(size.width / cell.width)),
                     rows: maxRows > 0 ? min(fitRows, maxRows) : fitRows)
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

    /// a cell as given
    private init(font: UIFont, width: CGFloat, height: CGFloat) {
        self.font = font
        self.width = width
        self.height = height
    }

    /// these cells in an 8-wide glyph's shape [rows] high: square for 8x8, twice as tall as wide for 8x16
    func shaped(rows: Int) -> CellMetrics { CellMetrics(font: font, width: width, height: width * CGFloat(rows) / 8) }

    /// the font size whose cells put [columns] across [width]
    init(columns: Int, across width: CGFloat) {
        let probe = UIFont.monospacedSystemFont(ofSize: 100, weight: .regular)
        let per = ("M" as NSString).size(withAttributes: [.font: probe]).width / 100
        self.init(fontSize: max(4, Double(width / (CGFloat(columns) * per)) - 0.01))
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
                // a line of double width or height: its first half's cells drawn twice the size
                // (only the top or bottom half of a double-height one shows on its row)
                let size = Int(model.bridge.lineSize(row: Int32(row)))
                var ctx = ctx
                if size != 0 {
                    ctx.clip(to: Path(CGRect(x: 0, y: y, width: .greatestFiniteMagnitude, height: cell.height)))
                    ctx.translateBy(x: 0, y: size == 3 ? y - cell.height : y)
                    ctx.scaleBy(x: 2, y: size == 1 ? 1 : 2)
                    ctx.translateBy(x: 0, y: -y)
                }
                for run in model.runs(row) {
                    let x = CGFloat(run.col) * cell.width
                    let w = CGFloat(run.cells) * cell.width
                    ctx.fill(Path(CGRect(x: x, y: y, width: w, height: cell.height)), with: .color(Color(argb: run.bg)))
                    if run.text.trimmingCharacters(in: .whitespaces).isEmpty { continue }
                    if model.bridge.bitmapGlyphs || (model.bridge.softGlyphs && run.text.unicodeScalars.contains { $0.value >= 0xF0000 }) {
                        drawGlyphs(ctx, run, y: y)
                        continue
                    }
                    var t = Text(run.text).font(run.bold ? bold : regular).foregroundColor(Color(argb: run.fg))
                    if run.italic { t = t.italic() }
                    if run.underline { t = t.underline() }
                    if run.strike { t = t.strikethrough() }
                    ctx.draw(t, at: CGPoint(x: x, y: y), anchor: .topLeading)
                }
                drawImages(ctx, row: row, y: y)
            }
            if model.bridge.cursorVisible {
                // (twice as wide on a line of double width)
                let wide: CGFloat = model.bridge.lineSize(row: model.bridge.cursorY) != 0 ? 2 : 1
                let r = CGRect(x: CGFloat(model.bridge.cursorX) * cell.width * wide, y: CGFloat(model.bridge.cursorY) * cell.height,
                               width: cell.width, height: cell.height)
                ctx.fill(Path(r), with: .color(Color(argb: model.bridge.cursorColor).opacity(0.6)))
            }
        }
    }
}

extension ScreenCanvas {
    /// a row's image tiles over its text, each run of them the image drawn clipped to its cells, scaled from the cell size it was made at
    func drawImages(_ ctx: GraphicsContext, row: Int, y: CGFloat) {
        for r in model.imageRuns(row) {
            guard let cg = model.cgImage(r) else { continue }
            let sx = cell.width / CGFloat(r.cellWidth)
            let sy = cell.height / CGFloat(r.cellHeight)
            let x = CGFloat(r.col) * cell.width
            var c = ctx
            c.clip(to: Path(CGRect(x: x, y: y, width: CGFloat(r.cells) * cell.width, height: cell.height)))
            let origin = CGPoint(x: x - CGFloat(r.tx * r.cellWidth) * sx, y: y - CGFloat(r.ty * r.cellHeight) * sy)
            let size = CGSize(width: CGFloat(r.width) * sx, height: CGFloat(r.height) * sy)
            c.draw(Image(decorative: cg, scale: 1).interpolation(.none), in: CGRect(origin: origin, size: size))
        }
    }

    /// a run's characters one cell each from the profile's bitmap glyphs (a pixel row's runs as one rectangle), the font's for one it hasn't
    func drawGlyphs(_ ctx: GraphicsContext, _ run: StyledRun, y: CGFloat) {
        let color = GraphicsContext.Shading.color(Color(argb: run.fg))
        for (i, ch) in run.text.unicodeScalars.enumerated() {
            let x = CGFloat(Int(run.col) + i) * cell.width
            guard let glyph = model.glyph(ch.value) else {
                if ch != " " { ctx.draw(Text(String(ch)).font(Font(cell.font as CTFont)).foregroundColor(Color(argb: run.fg)), at: CGPoint(x: x, y: y), anchor: .topLeading) }
                continue
            }
            var path = Path()
            let gw = glyph.width
            let high = 1 << (gw - 1)
            for (r, bits) in glyph.rows.enumerated() where bits != 0 {
                var c = 0
                while c < gw {
                    if bits & (high >> c) == 0 { c += 1; continue }
                    let start = c
                    while c < gw && bits & (high >> c) != 0 { c += 1 }
                    let h = CGFloat(glyph.rows.count)
                    let w = CGFloat(gw)
                    path.addRect(CGRect(x: x + CGFloat(start) * cell.width / w, y: y + CGFloat(r) * cell.height / h,
                                        width: CGFloat(c - start) * cell.width / w, height: cell.height / h))
                }
            }
            ctx.fill(path, with: color)
        }
    }
}

/// the keys a phone's keyboard lacks, as the profile's key bar has them
/// (the shared Kotlin's pages): Ctrl and Alt sticky, the last key the next page
private struct ExtraKeys: View {
    @ObservedObject var model: TerminalModel
    @State private var page = 0

    var body: some View {
        let pages = Int(model.bridge.keyBarPages)
        let p = pages == 0 ? 0 : page % pages
        let rows = model.bridge.keyBarRows(page: Int32(p))
        VStack(spacing: 4) {
            ForEach(Array(rows.enumerated()), id: \.offset) { r, labels in
                HStack(spacing: 4) {
                    ForEach(Array(labels.enumerated()), id: \.offset) { c, label in
                        key(label, page: p, row: r, col: c, pages: pages)
                    }
                }
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(.bar)
    }

    /// the key at ([row], [col]): Ctrl or Alt latched, the next page, or sent
    private func key(_ label: String, page p: Int, row: Int, col: Int, pages: Int) -> some View {
        let action = model.bridge.keyBarAction(page: Int32(p), row: Int32(row), col: Int32(col))
        let latched = (action == "ctrl" && model.ctrl) || (action == "alt" && model.alt) || (action == "next" && p != 0)
        // (a colour key shows its colour)
        let swatch = model.bridge.keyBarColor(page: Int32(p), row: Int32(row), col: Int32(col))
        return Button {
            switch action {
            case "ctrl": model.ctrl.toggle()
            case "alt": model.alt.toggle()
            case "next": page = (p + 1) % max(pages, 1)
            default: model.barKey(page: p, row: row, col: col)
            }
        } label: {
            Text(label).font(.system(size: 13, design: .monospaced)).lineLimit(1)
                .minimumScaleFactor(0.6).frame(maxWidth: .infinity, minHeight: 30)
        }
        .buttonStyle(.bordered)
        .tint(swatch != 0 ? Color(argb: swatch) : latched ? .accentColor : .secondary)
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
