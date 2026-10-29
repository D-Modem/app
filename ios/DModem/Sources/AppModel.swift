// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
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
    /// the fax calls, transfers and dial-up the modem is lent to
    let jobs: JobsModel
    private let store = SettingsStore()
    private let audio = ModemAudio()
    private let peer = PeerLineController()
    private let calls = CallController()
    private var peerTimer: Timer?
    /// the last of what the modem said, looked at for RING and the caller's number
    private var ringScan = ""
    private var callerId: String?
    /// what's typed at the terminal on its line, and the number its last ATD dialled
    private var typedLine = ""
    private var lastDial = ""

    /// the settings kept, and the session's changes, data, speaker and sound and the phone's call screen wired to it
    init() {
        settings = store.settings
        jobs = JobsModel(session: session) { [store] in store.settings }
        session.onChange = { [weak self] in
            // (many changes at once, one update)
            Task { @MainActor [weak self] in self?.refresh() }
        }
        session.onData = { [weak self] data in
            Task { @MainActor [weak self] in self?.received(data) }
        }
        session.onSpeaker = { [audio] level in audio.speaker(level) }
        session.onSound = { [audio] pcm in audio.play(pcm) }
        terminal.send = { [weak self] data in
            self?.typed(data)
            self?.session.send(data)
        }
        calls.onAnswer = { [weak self] in self?.session.command("ATA") }
        calls.onEnd = { [weak self] in self?.callEndedByUser() }
        calls.onAudio = { [audio] on in if on { audio.resume() } }
    }

    /// what the modem said (for the terminal): a remote's transfer started, a call ringing
    private func received(_ data: Data) {
        if jobs.remoteStarts(data) { return }
        terminal.feed(data)
        ringScan = String((ringScan + Octets.latin1(data)).suffix(256))
        // (caller ID, when the line gives it: NMBR = 5551234)
        if let r = ringScan.range(of: "NMBR = ", options: .backwards) {
            let n = ringScan[r.upperBound...].prefix { !$0.isNewline }
            if !n.isEmpty { callerId = String(n).trimmingCharacters(in: .whitespaces) }
        }
        if ringScan.contains("RING\r") || ringScan.contains("RING\n") {
            ringScan = ""
            ring()
        }
    }

    /// a RING: the phone's call screen rings (with the caller's number), and a fax is answered if it's set to (not while a job runs)
    private func ring() {
        if jobs.running { return }
        calls.ringing(from: callerId)
        if settings.faxAutoAnswer && jobs.answerFax() == nil { calls.answeredHere() }
    }

    /// the ringing call answered as a fax (the Fax screen's button)
    func answerFax() -> String? {
        let r = jobs.answerFax()
        if r == nil { calls.answeredHere() }
        return r
    }

    /// the terminal's typing, looked at for the number an ATD dials
    private func typed(_ data: Data) {
        for c in Octets.latin1(data) {
            if c == "\r" {
                let l = typedLine.uppercased().trimmingCharacters(in: .whitespaces)
                if let r = l.range(of: "ATD") {
                    lastDial = String(l[r.upperBound...].drop { $0 == "T" || $0 == "P" }.filter { $0.isNumber || $0 == "*" || $0 == "#" || $0 == "+" })
                }
                typedLine = ""
            } else if c == "\u{8}" || c == "\u{7f}" {
                if !typedLine.isEmpty { typedLine.removeLast() }
            } else if typedLine.count < 200 {
                typedLine.append(c)
            }
        }
    }

    /// hung up on the phone's call screen: the job's call, or the modem's, over
    private func callEndedByUser() {
        if jobs.fax?.running == true { jobs.cancelFax() }
        if jobs.internet?.running == true { jobs.disconnectInternet() }
        if jobs.transfer?.running == true { jobs.cancelTransfer() }
        if status.offHook { hangUp() }
    }

    /// the number being dialled, for the phone's call screen
    private var dialling: String {
        if jobs.fax?.running == true, jobs.fax?.sending == true { return jobs.fax?.number ?? "" }
        if jobs.internet?.running == true { return settings.ispNumber }
        return lastDial
    }

    var connectLabel: String { status.link == .online ? ConnectInfo.label(console) : "" }

    /// the modem started with the settings: the background audio, the peer line if there's one, then the session
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
            if s.peer && !s.noLine { peer.start(s, session: session) } else { session.onLine = nil }
            if !session.start(s, dns: SystemDNS.servers(), dataFile: dataFile) {
                peer.stop()
                audio.end()
            }
        }
        watchPeer(s.peer && !s.noLine)
    }

    /// the modem, the peer line and the background audio stopped
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

    /// the modem's call hung up (off the main thread)
    func hangUp() {
        Task.detached { [session] in session.hangUp() }
    }

    /// an AT command (Home's line)
    func command(_ cmd: String) { session.command(cmd) }

    /// the session's state copied in, and the call screen, the jobs and the audio told what changed
    private func refresh() {
        let wasOnline = status.link == .online
        let wasRunning = run == .running
        let wasOffHook = status.offHook
        run = session.run
        status = session.status
        log = session.log
        console = session.console
        error = session.error
        if status.link == .online && !wasOnline { onlineSince = Date() }
        if status.link != .online { onlineSince = nil }
        // the phone's call screen: a call ringing answered, one dialled, connected, over
        if status.offHook && !wasOffHook {
            if calls.inCall { calls.answeredHere() } else { calls.dialling(dialling) }
        }
        if status.link == .online && !wasOnline { calls.connected() }
        if (!status.offHook && wasOffHook) || (wasRunning && run == .stopped) { calls.ended() }
        jobs.statusChanged(online: status.link == .online, running: run == .running)
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
