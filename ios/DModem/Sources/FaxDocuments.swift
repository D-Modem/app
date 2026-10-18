// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import CoreGraphics
import DModemShared
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Documents as fax pages, as the Android app's FaxDocuments: a PDF's
/// pages, an image, plain text, each drawn in grey at the fax's width
/// (1728 pels, 204 to the inch; 196 or 98 lines to the inch down), made
/// black and white by the shared FaxImage (text and PDFs at a threshold,
/// photographs dithered).
enum FaxDocuments {
    enum Failure: LocalizedError {
        case unreadable(String)
        var errorDescription: String? {
            switch self { case .unreadable(let why): return why }
        }
    }

    static let width = Int(FaxPage.companion.WIDTH)

    /// the pages of the document at [url] (a file the app may read)
    static func pages(_ url: URL, fine: Bool) throws -> [FaxPage] {
        let type = UTType(filenameExtension: url.pathExtension.lowercased())
        if type?.conforms(to: .pdf) == true { return try pdf(url, fine: fine) }
        if type?.conforms(to: .image) == true {
            guard let img = UIImage(contentsOfFile: url.path)?.cgImage else { throw Failure.unreadable("can't read the image") }
            return [page(fine: fine, dither: true, aspect: CGSize(width: img.width, height: img.height)) { ctx, size in
                ctx.interpolationQuality = .high
                ctx.draw(img, in: CGRect(origin: .zero, size: size))
            }]
        }
        if type?.conforms(to: .text) == true || url.pathExtension.lowercased() == "txt" {
            guard let d = try? Data(contentsOf: url) else { throw Failure.unreadable("can't read the text") }
            return text(String(decoding: d, as: UTF8.self), fine: fine)
        }
        throw Failure.unreadable("not a PDF, an image or text: \(url.lastPathComponent)")
    }

    private static func pdf(_ url: URL, fine: Bool) throws -> [FaxPage] {
        guard let doc = CGPDFDocument(url as CFURL) else { throw Failure.unreadable("can't open the PDF") }
        guard doc.numberOfPages > 0 else { return [] }
        return (1...doc.numberOfPages).compactMap { n in
            guard let pg = doc.page(at: n) else { return nil }
            let box = pg.getBoxRect(.mediaBox)
            return page(fine: fine, dither: false, aspect: box.size) { ctx, size in
                ctx.scaleBy(x: size.width / box.width, y: size.height / box.height)
                ctx.translateBy(x: -box.minX, y: -box.minY)
                ctx.drawPDFPage(pg)
            }
        }
    }

    /// plain text on A4 pages: monospaced, 66 lines and 80 columns to a page
    static func text(_ text: String, fine: Bool) -> [FaxPage] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\t", with: " ")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .flatMap { l -> [String] in
                if l.isEmpty { return [""] }
                return stride(from: 0, to: l.count, by: 80).map { i in
                    let a = l.index(l.startIndex, offsetBy: i)
                    return String(l[a..<(l.index(a, offsetBy: 80, limitedBy: l.endIndex) ?? l.endIndex)])
                }
            }
        let perPage = 66
        let chunks = stride(from: 0, to: max(lines.count, 1), by: perPage).map { Array(lines[$0..<min($0 + perPage, lines.count)]) }
        // (drawn on a fine page's proportions: A4, 1728 across)
        let fineRows = CGFloat(FaxPage.companion.a4Rows(fine: true))
        return chunks.map { chunk in
            page(fine: fine, dither: false, aspect: CGSize(width: CGFloat(width), height: fineRows * 204 / 196)) { ctx, size in
                // (UIKit's text, top down)
                ctx.translateBy(x: 0, y: size.height)
                ctx.scaleBy(x: size.width / CGFloat(width), y: -size.height / fineRows)
                UIGraphicsPushContext(ctx)
                let font = UIFont.monospacedSystemFont(ofSize: 1500 / 80 / 0.6, weight: .regular)
                let top: CGFloat = 120
                let step = (fineRows - 2 * top) / CGFloat(perPage)
                for (i, l) in chunk.enumerated() {
                    (l as NSString).draw(at: CGPoint(x: 110, y: top + CGFloat(i) * step), withAttributes: [.font: font, .foregroundColor: UIColor.black])
                }
                UIGraphicsPopContext()
            }
        }
    }

    /// a page drawn by [draw] (in a grey context [width] across, as tall as [aspect] makes it at the
    /// fax's resolution, its origin bottom left), made black and white
    private static func page(fine: Bool, dither: Bool, aspect: CGSize, draw: (CGContext, CGSize) -> Void) -> FaxPage {
        let rows = Int(FaxImage.shared.rows(width: Int32(max(1, aspect.width)), height: Int32(max(1, aspect.height)), fine: fine))
        var gray = [UInt8](repeating: 255, count: width * rows)
        gray.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: width, height: rows, bitsPerComponent: 8, bytesPerRow: width,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
            ctx.setFillColor(gray: 1, alpha: 1)
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: rows))
            draw(ctx, CGSize(width: width, height: rows))
        }
        // (a bitmap context's memory is its top row first, as the fax's)
        let latin1 = String(data: Data(gray), encoding: .isoLatin1) ?? ""
        return FaxImage.shared.pageLatin1(gray: latin1, width: Int32(width), height: Int32(rows), fine: fine, dither: dither)
    }
}
