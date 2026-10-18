// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import Foundation
import SwiftUI

/// The app's state for its views: the modem session (DModemKit) brought to
/// the main thread, the settings kept, the speaker and the background
/// audio that keeps a call going when the app isn't in front.
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var run = ModemSession.Run.stopped
    @Published private(set) var status = ModemStatus()
    @Published private(set) var log: [String] = []
    @Published private(set) var console = ""
    @Published private(set) var error: String?
    /// when the call came online (for its time on Home)
    @Published private(set) var onlineSince: Date?
    @Published var settings: ModemSettings {
        didSet { store.settings = settings }
    }

    let session = ModemSession()
    let terminal = TerminalModel()
    private let store = SettingsStore()
    private let audio = ModemAudio()
    private var pending = false

    init() {
        settings = store.settings
        session.onChange = { [weak self] in
            // (many changes at once, one update)
            Task { @MainActor [weak self] in self?.refresh() }
        }
        session.onData = { [weak self] data in
            Task { @MainActor [weak self] in self?.terminal.feed(data) }
        }
        session.onSpeaker = { [audio] level in audio.speaker(level) }
        session.onSound = { [audio] pcm in audio.play(pcm) }
        terminal.send = { [weak self] data in self?.session.send(data) }
    }

    var connectLabel: String { status.link == .online ? ConnectInfo.label(console) : "" }

    func start() {
        error = nil
        audio.enabled = settings.speaker
        let dataFile = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            .map { url -> String in
                try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
                return url.appendingPathComponent("modem-data").path
            } ?? ""
        let s = settings
        Task.detached { [session, audio] in
            // (the background audio first: the call keeps the app running)
            audio.begin()
            if !session.start(s, dns: SystemDNS.servers(), dataFile: dataFile) { audio.end() }
        }
    }

    func stop() {
        Task.detached { [session, audio] in
            session.stop()
            audio.end()
        }
    }

    func hangUp() {
        Task.detached { [session] in session.hangUp() }
    }

    /// an AT command (Home's line)
    func command(_ cmd: String) { session.command(cmd) }

    private func refresh() {
        let wasOnline = status.link == .online
        run = session.run
        status = session.status
        log = session.log
        console = session.console
        error = session.error
        if status.link == .online && !wasOnline { onlineSince = Date() }
        if status.link != .online { onlineSince = nil }
        if run == .stopped { audio.end() }
    }
}
