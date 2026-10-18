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
    /// the line to another phone: what it's doing, what went wrong last (nil on a SIP line)
    @Published private(set) var peerState: String?
    @Published private(set) var peerError: String?
    @Published var settings: ModemSettings {
        didSet { store.settings = settings }
    }

    let session = ModemSession()
    let terminal = TerminalModel()
    private let store = SettingsStore()
    private let audio = ModemAudio()
    private let peer = PeerLineController()
    private var peerTimer: Timer?

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
        Task.detached { [session, audio, peer] in
            // (the background audio first: the call keeps the app running)
            audio.begin()
            // (the line to another phone first: the modem's start commands may dial)
            if s.peer { peer.start(s, session: session) } else { session.onLine = nil }
            if !session.start(s, dns: SystemDNS.servers(), dataFile: dataFile) {
                peer.stop()
                audio.end()
            }
        }
        watchPeer(s.peer)
    }

    func stop() {
        Task.detached { [session, audio, peer] in
            session.stop()
            peer.stop()
            audio.end()
        }
        watchPeer(false)
    }

    /// the peer line's state, twice a second while there's one
    private func watchPeer(_ on: Bool) {
        peerTimer?.invalidate()
        peerTimer = nil
        peerState = nil
        peerError = nil
        guard on else { return }
        peerTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let st = self.peer.stateText
                let err = self.peer.lastError
                if st != self.peerState { self.peerState = st }
                if err != self.peerError { self.peerError = err }
            }
        }
    }

    func hangUp() {
        Task.detached { [session] in session.hangUp() }
    }

    /// an AT command (Home's line)
    func command(_ cmd: String) { session.command(cmd) }

    private func refresh() {
        let wasOnline = status.link == .online
        let wasRunning = run == .running
        run = session.run
        status = session.status
        log = session.log
        console = session.console
        error = session.error
        if status.link == .online && !wasOnline { onlineSince = Date() }
        if status.link != .online { onlineSince = nil }
        // (the modem stopped by itself: the call over, the line to the other phone too. Only a stop
        // after it ran: while it starts, its log comes before it's running)
        if wasRunning && run == .stopped {
            audio.end()
            if peerTimer != nil {
                peer.stop()
                watchPeer(false)
            }
        }
    }
}
