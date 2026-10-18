// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation

/// What a CONNECT result says beyond the rate ("CONNECT 1200/CVM/LAPM",
/// "CONNECT 56000/V92/LAPM/V44"), named as Home shows it ("V.cvm · LAPM").
/// The Android app's ConnectInfo.
public enum ConnectInfo {
    static let names: [String: String] = [
        "CVM": "V.cvm", "V92": "V.92", "V90": "V.90", "V91": "V.91", "V34": "V.34",
        "V32B": "V.32bis", "V32": "V.32", "V22B": "V.22bis", "V22": "V.22", "V23": "V.23",
        "V21": "V.21", "B103": "Bell 103", "B212": "Bell 212", "LAPM": "LAPM", "MNP": "MNP",
        "V44": "V.44", "V42BIS": "V.42bis", "MNP5": "MNP 5",
    ]

    /// the last CONNECT line in [console]: its parts after the rate, named; empty when there's none
    public static func label(_ console: String) -> String {
        guard let r = console.range(of: "CONNECT", options: .backwards) else { return "" }
        let line = console[r.upperBound...].prefix { !$0.isNewline }
        return line.trimmingCharacters(in: .whitespaces).split(separator: "/").dropFirst()
            .compactMap { names[$0.trimmingCharacters(in: .whitespaces).uppercased()] }
            .joined(separator: " · ")
    }
}
