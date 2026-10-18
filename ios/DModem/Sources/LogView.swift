// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import SwiftUI

/// The modem's and SIP's log, the newest at the bottom; shared as text.
struct LogView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollViewReader { proxy in
            List(Array(model.log.enumerated()), id: \.offset) { item in
                Text(item.element).font(.caption.monospaced()).textSelection(.enabled).id(item.offset)
            }
            .listStyle(.plain)
            .onChange(of: model.log.count) { n in
                if n > 0 { proxy.scrollTo(n - 1, anchor: .bottom) }
            }
        }
        .navigationTitle("Log")
        .toolbar {
            ShareLink(item: model.log.joined(separator: "\n"))
        }
    }
}
