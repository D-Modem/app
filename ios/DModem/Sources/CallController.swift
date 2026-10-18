// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import AVFoundation
import CallKit
import Foundation

/// The modem's calls as the phone's own (CallKit): a call ringing in
/// (the modem's RING) rings as a phone call does, on the lock screen too,
/// answered or turned down there; a call the modem dials shows in the
/// status bar as a call, hung up from the phone's call screen; each in
/// Recents. (Calls come in while the app runs: its background audio keeps
/// it running with the modem. Without a push service, a call can't wake
/// an app that isn't.)
@MainActor
final class CallController: NSObject {
    /// the user answered the ringing call (on the phone's screen)
    var onAnswer: (() -> Void)?
    /// the user hung up (or turned down a ringing call)
    var onEnd: (() -> Void)?
    /// the call's audio session active (true) or not: the modem's sound resumed on it
    var onAudio: ((Bool) -> Void)?

    private let provider: CXProvider
    private let calls = CXCallController()
    private var current: UUID?
    private var incoming = false
    private var answered = false
    /// the call ended by us (the modem), not the user: no hang-up asked of it
    private var endingOurselves = false
    private var ringTimer: Timer?
    /// the ringing call turned down: its RINGs passed over until they stop
    private var declined = false

    override init() {
        let c = CXProviderConfiguration()
        c.supportsVideo = false
        c.maximumCallGroups = 1
        c.maximumCallsPerCallGroup = 1
        c.supportedHandleTypes = [.phoneNumber, .generic]
        c.includesCallsInRecents = true
        provider = CXProvider(configuration: c)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    var inCall: Bool { current != nil }

    /// the modem said RING: the phone rings (again: the ring goes on while RINGs come)
    func ringing(from caller: String?) {
        ringTimer?.invalidate()
        // (no RING for 8 s: the caller gave up)
        ringTimer = Timer.scheduledTimer(withTimeInterval: 8, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in self?.ringStopped() }
        }
        if current != nil || declined { return }
        let id = UUID()
        current = id
        incoming = true
        answered = false
        let u = CXCallUpdate()
        u.remoteHandle = CXHandle(type: caller == nil ? .generic : .phoneNumber, value: caller ?? "Modem call")
        u.localizedCallerName = caller.map { "Modem call from \($0)" } ?? "Modem call"
        u.hasVideo = false
        u.supportsHolding = false
        u.supportsGrouping = false
        u.supportsUngrouping = false
        u.supportsDTMF = false
        provider.reportNewIncomingCall(with: id, update: u) { [weak self] err in
            if err != nil { Task { @MainActor [weak self] in self?.forget(id) } }
        }
    }

    /// the ringing call answered by the app itself (a fax answered): the phone's ring stopped, the call on
    func answeredHere() {
        guard let id = current, incoming, !answered else { return }
        answered = true
        ringTimer?.invalidate()
        calls.request(CXTransaction(action: CXAnswerCallAction(call: id))) { _ in }
    }

    /// the modem off hook dialling [number] (not answering): an outgoing call
    func dialling(_ number: String) {
        guard current == nil else { return }
        let id = UUID()
        current = id
        incoming = false
        let h = CXHandle(type: number.isEmpty ? .generic : .phoneNumber, value: number.isEmpty ? "Modem call" : number)
        let a = CXStartCallAction(call: id, handle: h)
        calls.request(CXTransaction(action: a)) { [weak self] err in
            if err != nil { Task { @MainActor [weak self] in self?.forget(id) } }
        }
    }

    /// the modems connected
    func connected() {
        guard let id = current else { return }
        if !incoming { provider.reportOutgoingCall(with: id, connectedAt: nil) }
    }

    /// the modem back on hook: the call over
    func ended() {
        guard let id = current else { return }
        ringTimer?.invalidate()
        endingOurselves = true
        calls.request(CXTransaction(action: CXEndCallAction(call: id))) { [weak self] err in
            if err != nil {
                Task { @MainActor [weak self] in
                    self?.provider.reportCall(with: id, endedAt: nil, reason: .remoteEnded)
                    self?.forget(id)
                }
            }
        }
    }

    private func ringStopped() {
        declined = false
        guard let id = current, incoming, !answered else { return }
        provider.reportCall(with: id, endedAt: nil, reason: .unanswered)
        forget(id)
    }

    private func forget(_ id: UUID) {
        if current == id {
            current = nil
            endingOurselves = false
        }
    }
}

extension CallController: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor in self.current = nil }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in
            // (answered here already, a fax: nothing to tell the modem)
            let byUser = !self.answered
            self.answered = true
            self.ringTimer?.invalidate()
            if byUser { self.onAnswer?() }
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor in
            let byUser = !self.endingOurselves
            if byUser && self.incoming && !self.answered { self.declined = true }
            self.forget(action.callUUID)
            if byUser { self.onEnd?() }
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        Task { @MainActor in self.onAudio?(true) }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in self.onAudio?(false) }
    }
}
