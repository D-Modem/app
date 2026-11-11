// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import DModemShared
import Foundation

/// The modem lent to one job at a time, as the shared Kotlin runs them: a
/// fax call, a file transfer, dial-up internet. The DTE is the job's while
/// it lasts (the terminal sees none of it), back to the terminal after
/// with what the job didn't read.
@MainActor
final class JobsModel: ObservableObject {
    /// the fax call (going, or how the last one ended)
    @Published private(set) var fax: FaxJob.State?
    /// the transfer (going, or how the last one ended), and which way
    @Published private(set) var transfer: TransferState?
    @Published private(set) var transferSending = false
    /// the dial-up link (going, or how the last one ended)
    @Published private(set) var internet: DialUpJob.State?
    /// the remote waits for files (rz): the terminal offers to pick some
    @Published var filesWanted = false
    /// pages being drawn for a fax
    @Published private(set) var preparing = false
    /// why the last fax couldn't start (its document unreadable)
    @Published var faxError: String?
    /// the voice call (going, or how the last one ended)
    @Published private(set) var voice: VoiceJob.State?
    /// a message being left heard on the loudspeaker; the microphone muted; the loudspeaker (else the earpiece)
    @Published private(set) var listening = false
    @Published private(set) var muted = false
    @Published private(set) var loud = true
    /// the voice call over: the modem's own sound back (the app's)
    var voiceEnded: (() -> Void)?

    private let session: ModemSession
    private let settings: () -> ModemSettings
    private var faxJob: FaxJob?
    private var transferJob: TransferJob?
    private var dialUp: DialUpJob?
    private var voiceJob: VoiceJob?
    private let sound = VoiceSound()
    private var pump: VoicePump?
    private var tunnel: UdpPacketTunnel?
    private var sawOnline = false

    /// jobs on [session], with the settings as [settings] gives them at each start
    init(session: ModemSession, settings: @escaping () -> ModemSettings) {
        self.session = session
        self.settings = settings
    }

    var running: Bool { faxJob != nil || transferJob != nil || dialUp != nil || voiceJob != nil || preparing }

    /// why a job can't start now (a call to make: not while the modem's in one), or nil
    private func busy(call: Bool) -> String? {
        if session.run != .running { return "the modem is off" }
        if running || session.claimed { return "the modem is in use" }
        if call && session.status.offHook { return "the modem is in a call" }
        return nil
    }

    /// the DTE to [job]'s feed: false (and nothing started) if it can't be had
    private func lend(_ feed: @escaping (String) -> Void) -> Bool {
        session.claim { d in feed(Octets.latin1(d)) }
    }

    /// a link for a job, whose changes run [refresh] on this model
    private func link(_ refresh: @escaping @MainActor (JobsModel) -> Void) -> JobLink {
        JobLink(session: session) { [weak self] in
            guard let self else { return }
            refresh(self)
        }
    }

    // MARK: fax

    /// [documents]' pages faxed to [number]: nil when it's started, else why it can't
    func sendFax(number: String, documents: [URL]) -> String? {
        if let b = busy(call: true) { return b }
        let n = number.trimmingCharacters(in: .whitespaces)
        if n.isEmpty { return "no number" }
        if documents.isEmpty { return "no document" }
        let s = settings()
        faxError = nil
        preparing = true
        Task.detached { [weak self] in
            let pages: Result<[FaxPage], Error> = Result { try documents.flatMap { try FaxDocuments.pages($0, fine: s.faxFine) } }
            await self?.faxPagesReady(pages, number: n, settings: s)
        }
        return nil
    }

    /// the fax's pages drawn: the DTE lent and the fax sent, or why not in [faxError]
    private func faxPagesReady(_ pages: Result<[FaxPage], Error>, number: String, settings s: ModemSettings) {
        preparing = false
        let l = link { $0.refreshFax() }
        let job = FaxJob(out: l, log: l, changed: l)
        switch pages {
        case .failure(let e):
            faxError = e.localizedDescription
            return
        case .success(let p):
            guard lend({ job.feed(latin1: $0) }) else {
                faxError = "the modem is in use"
                return
            }
            faxJob = job
            job.send(number: number, pages: p, ident: s.faxIdent, ecm: s.faxEcm, maxBps: Int32(s.faxMaxBps))
        }
    }

    /// the call ringing answered as a fax: nil when it's started, else why it can't
    func answerFax() -> String? {
        if let b = busy(call: false) { return b }
        let s = settings()
        let l = link { $0.refreshFax() }
        let job = FaxJob(out: l, log: l, changed: l)
        guard lend({ job.feed(latin1: $0) }) else { return "the modem is in use" }
        faxJob = job
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        job.answer(ident: s.faxIdent, ecm: s.faxEcm, maxBps: Int32(s.faxMaxBps),
                   into: PosixFolderIncoming(dir: AppFolders.faxes.path), fileName: "fax-\(f.string(from: Date())).pdf")
        return nil
    }

    /// the fax call hung up
    func cancelFax() { faxJob?.cancel() }

    /// the last fax's outcome cleared (not while one's going)
    func dismissFax() { if faxJob == nil { fax = nil; faxError = nil } }

    /// the fax job's state copied in; once it's over, the DTE back with what it didn't read
    private func refreshFax() {
        guard let job = faxJob else { return }
        fax = job.state
        if job.state?.running == false {
            faxJob = nil
            session.release(leftover: Octets.data(job.leftover))
        }
    }

    // MARK: voice calls

    /// a voice job lent the DTE, [begin] it: nil, or why not
    private func startVoice(call: Bool, _ begin: (VoiceJob) -> Void) -> String? {
        if let b = busy(call: call) { return b }
        let l = link { $0.refreshVoice() }
        let job = VoiceJob(out: l, log: l, changed: l)
        guard lend({ job.feed(latin1: $0) }) else { return "the modem is in use" }
        voiceJob = job
        listening = false
        muted = false
        pump = VoicePump(job: job, sound: sound)
        begin(job)
        voice = job.state
        return nil
    }

    /// [number] called, to talk: nil when it's started, else why it can't
    func voiceDial(_ number: String) -> String? {
        let n = number.trimmingCharacters(in: .whitespaces)
        if n.isEmpty { return "no number" }
        return startVoice(call: true) { $0.dial(number: n) }
    }

    /// the call ringing answered, to talk ([caller]: caller ID's number)
    func voiceAnswer(caller: String) -> String? {
        startVoice(call: false) { $0.answer(caller: caller) }
    }

    /// the call ringing answered by the answering machine: its greeting played, the message kept in Documents/Voicemail
    func takeMessage(caller: String, maxSeconds: Int) -> String? {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        let name = VoiceJob.companion.messageName(time: f.string(from: Date()), caller: caller)
        let greeting = VoiceFiles.decode(VoiceFiles.greeting)
        let dir = VoiceFiles.messages.path
        return startVoice(call: false) {
            $0.takeMessage(greeting: greeting, maxS: Int32(maxSeconds), silenceS: VoiceJob.companion.MESSAGE_SILENCE_S,
                           into: PosixFolderIncoming(dir: dir), fileName: name, caller: caller)
        }
    }

    /// the answering machine's call taken, to talk
    func pickUp() {
        guard let j = voiceJob, j.pickUp() else { return }
        listening = false
        voiceSound()
    }

    /// the message being left heard (or not)
    func listen(_ on: Bool) {
        guard voiceJob?.state?.machine == true else { return }
        listening = on
        voiceSound()
    }

    /// the voice call hung up
    func hangUpVoice() { voiceJob?.hangUp() }

    /// DTMF digit [key] played to the far end
    func dtmf(_ key: Character) { _ = voiceJob?.dtmf(d: key.utf16.first ?? 0) }

    /// our microphone muted (the far end hears silence) or not
    func mute(_ on: Bool) {
        muted = on
        voiceJob?.muted = on
    }

    /// the call's sound on the loudspeaker (else the earpiece)
    func loudspeaker(_ on: Bool) {
        loud = on
        sound.loudspeaker(on)
    }

    /// the last voice call's outcome cleared (not while one's going)
    func dismissVoice() { if voiceJob == nil { voice = nil } }

    /// the sound as the call needs it: speaker and microphone when talking, the speaker alone when listening, none else
    private func voiceSound() {
        guard let j = voiceJob, let st = j.state, st.running else { return }
        let talking = st.phase == VoiceJob.Phase.talking
        let machine = st.machine && st.phase != VoiceJob.Phase.ending
        if talking || (machine && listening) {
            if !sound.running || talking { _ = sound.start(record: talking, loud: talking ? loud : true) }
            pump?.start()
        } else if sound.running {
            pump?.stop()
            sound.stop()
        }
    }

    /// the voice job's state copied in, the sound started or stopped; once it's over, the DTE back
    private func refreshVoice() {
        guard let job = voiceJob else { return }
        let before = voice?.phase
        voice = job.state
        if let st = job.state, st.phase != before { voiceSound() }
        if job.state?.phase == VoiceJob.Phase.ending || job.state?.running == false {
            pump?.stop()
            sound.stop()
        }
        if job.state?.running == false {
            voiceJob = nil
            pump = nil
            listening = false
            session.release(leftover: Octets.data(job.leftover))
            voiceEnded?()
        }
    }

    // MARK: transfers

    /// the protocols' names (ZMODEM …), and what each is called
    static var protocols: [String] { TransferJob.companion.protocols }
    /// what protocol [p] is called
    static func label(_ p: String) -> String { TransferJob.companion.label(protocolName: p) }
    /// whether protocol [p] takes a batch of files (else one at a time)
    static func batch(_ p: String) -> Bool { TransferJob.companion.batch(protocolName: p) }

    /// files received by [proto] into Documents/Received ([first]: what came that started it)
    @discardableResult
    func receiveFiles(_ proto: String, first: Data = Data()) -> String? {
        if let b = busy(call: false) { return b }
        let l = link { $0.refreshTransfer() }
        let job = TransferJob(protocolName: proto, sending: false, out: l, log: l, changed: l)
        guard lend({ job.feed(latin1: $0) }) else { return "the modem is in use" }
        transferJob = job
        transferSending = false
        filesWanted = false
        job.receive(into: PosixFolderIncoming(dir: AppFolders.received.path), name: "received.bin")
        if !first.isEmpty { job.feed(latin1: Octets.latin1(first)) }
        transfer = job.state
        return nil
    }

    /// [files] sent by [proto] (copied first: a picked file may only be read while it's open to us)
    @discardableResult
    func sendFiles(_ proto: String, files: [URL]) -> String? {
        if let b = busy(call: false) { return b }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("send-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        var out: [Outgoing] = []
        for u in files {
            let scoped = u.startAccessingSecurityScopedResource()
            defer { if scoped { u.stopAccessingSecurityScopedResource() } }
            let dest = tmp.appendingPathComponent(u.lastPathComponent)
            do { try FileManager.default.copyItem(at: u, to: dest) } catch { return "can't read \(u.lastPathComponent)" }
            guard let o = PosixFiles.shared.outgoing(path: dest.path) else { return "can't read \(u.lastPathComponent)" }
            out.append(o)
        }
        if out.isEmpty { return "no files" }
        let l = link { $0.refreshTransfer() }
        let job = TransferJob(protocolName: proto, sending: true, out: l, log: l, changed: l)
        guard lend({ job.feed(latin1: $0) }) else { return "the modem is in use" }
        transferJob = job
        transferSending = true
        filesWanted = false
        job.send(files: out)
        transfer = job.state
        return nil
    }

    /// the transfer cancelled
    func cancelTransfer() { transferJob?.cancel() }

    /// the last transfer's outcome cleared (not while one's going)
    func dismissTransfer() { if transferJob == nil { transfer = nil } }

    /// the transfer's state copied in; once it's over, the DTE back with what it didn't read
    private func refreshTransfer() {
        guard let job = transferJob else { return }
        transfer = job.state
        if !job.state.running {
            transferJob = nil
            session.release(leftover: Octets.data(job.leftover))
        }
    }

    /// what came for the terminal, looked at for a remote starting a transfer: true when a job took it
    func remoteStarts(_ data: Data) -> Bool {
        if running { return false }
        switch TransferJob.companion.detect(latin1: Octets.latin1(data)) {
        case "ZMODEM_SEND": return receiveFiles("ZMODEM", first: data) == nil
        case "KERMIT_SEND": return receiveFiles("KERMIT", first: data) == nil
        case "ZMODEM_RECEIVE":
            filesWanted = true
            return false
        default: return false
        }
    }

    // MARK: dial-up internet

    /// the ISP dialled and PPP up, the phone's traffic through it: nil when it's started, else why not
    func connectInternet() -> String? {
        if let b = busy(call: true) { return b }
        let s = settings()
        if s.ispNumber.trimmingCharacters(in: .whitespaces).isEmpty { return "no number for the ISP" }
        let l = link { $0.refreshInternet() }
        let job = DialUpJob(out: l, log: l, changed: l)
        guard lend({ job.feed(latin1: $0) }) else { return "the modem is in use" }
        let t = UdpPacketTunnel(job: job, starter: VpnTunnel())
        dialUp = job
        tunnel = t
        sawOnline = false
        job.connect(number: s.ispNumber, user: s.ispUser, password: s.ispPassword, script: s.ispScript,
                    initCommands: s.ispInit, tunnel: t)
        internet = job.state
        return nil
    }

    /// the dial-up link hung up
    func disconnectInternet() { dialUp?.disconnect() }

    /// how long the link has been up (ms)
    var upMs: Int64 { dialUp?.upMs ?? 0 }

    /// the last dial-up's outcome cleared (not while one's going)
    func dismissInternet() { if dialUp == nil { internet = nil } }

    /// the modem's status changed: the link down when the call is
    func statusChanged(online: Bool, running: Bool) {
        guard let d = dialUp else { return }
        if !running { d.lineDropped(); return }
        // (once the modem has said it's online: one that doesn't say isn't watched)
        if online { sawOnline = true }
        if sawOnline && !online && d.state.connected { d.lineDropped() }
    }

    /// the dial-up's state copied in; once it's over, the tunnel dropped and the DTE back with what it didn't read
    private func refreshInternet() {
        guard let job = dialUp else { return }
        internet = job.state
        if !job.state.running {
            dialUp = nil
            tunnel = nil
            session.release(leftover: Octets.data(job.leftover))
        }
    }
}
