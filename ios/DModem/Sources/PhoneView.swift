// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import AVFoundation
import DModemKit
import DModemShared
import SwiftUI
import UniformTypeIdentifiers

/// Voice calls through the modem: a number called, the ringing call
/// answered (to talk, or by the answering machine), the call as it goes
/// (mute, the loudspeaker, the keypad; a message being left heard or
/// picked up); the answering machine, off until it's turned on, its
/// greeting and its messages (WAV files in Documents/Voicemail, on this
/// phone only).
struct PhoneView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var jobs: JobsModel
    @State private var number = ""
    @State private var error: String?
    @State private var messages: [URL] = []
    @State private var keypad = false
    @State private var choosing = false
    @StateObject private var player = VoicePlayer()
    @StateObject private var greeting = GreetingRecorder()

    var body: some View {
        Form {
            if let v = jobs.voice { callSection(v) }
            Section {
                TextField("Number", text: $number).keyboardType(.phonePad)
                Button("Call") { ask { error = jobs.voiceDial(number) } }
                    .disabled(number.isEmpty || jobs.running || model.run != .running)
            } header: {
                Text("Call")
            } footer: {
                Text("A voice call through the modem's line, on this phone's speaker and microphone.")
            }
            Section("Ringing call") {
                Button("Answer to talk") { ask { error = model.answerVoice() } }
                    .disabled(jobs.running || model.run != .running)
                Button("Take a message") { error = model.takeMessage() }
                    .disabled(jobs.running || model.run != .running)
            }
            if let e = error {
                Section { Text(e).foregroundStyle(.red) }
            }
            Section {
                Toggle("Answering machine", isOn: $model.settings.voicemail)
                Stepper("After \(model.settings.voicemailRings) ring\(model.settings.voicemailRings == 1 ? "" : "s")",
                        value: $model.settings.voicemailRings, in: 1...10)
                Picker("Longest message", selection: $model.settings.voicemailMaxSeconds) {
                    Text("1 minute").tag(60)
                    Text("2 minutes").tag(120)
                    Text("5 minutes").tag(300)
                }
                HStack {
                    Text(greeting.recording ? "Recording: speak now" : greeting.seconds > 0 ? "Greeting: \(greeting.seconds) s" : "Greeting: none, callers hear a beep")
                    Spacer()
                }
                HStack(spacing: 16) {
                    if greeting.recording {
                        Button("Stop") { greeting.stop() }
                    } else {
                        Button("Record") { ask { greeting.record() } }
                        Button("Play") { player.play(VoiceFiles.greeting) }.disabled(greeting.seconds == 0)
                        Button("File…") { choosing = true }
                        Button("None") { greeting.clear() }.disabled(greeting.seconds == 0)
                    }
                }
                .buttonStyle(.borderless)
            } header: {
                Text("Answering machine")
            } footer: {
                Text(model.settings.faxAutoAnswer
                     ? "Fax answers every call first: the answering machine waits until that's off."
                     : "It answers the calls nobody does, plays the greeting and a beep, and keeps the message on this phone.")
            }
            Section("Messages") {
                if messages.isEmpty { Text("None yet").foregroundStyle(.secondary) }
                ForEach(messages, id: \.self) { u in messageRow(u) }
                    .onDelete { i in
                        for k in i { try? FileManager.default.removeItem(at: messages[k]) }
                        reload()
                    }
            }
        }
        .navigationTitle("Phone")
        .onAppear { reload(); greeting.reload() }
        .onChange(of: jobs.voice?.running) { _ in reload() }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [UTType.wav]) { r in
            if case .success(let u) = r { error = greeting.choose(u) }
        }
    }

    /// the microphone allowed first (asked, if it hasn't been), then [then]
    private func ask(_ then: @escaping () -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { ok in
            DispatchQueue.main.async {
                if ok { then() } else { error = "A call needs the microphone: allow it in Settings" }
            }
        }
    }

    /// the call now or just ended
    @ViewBuilder
    private func callSection(_ v: VoiceJob.State) -> some View {
        Section {
            let who = v.number.isEmpty ? (v.calling ? "the number" : "an unknown caller") : v.number
            Text(v.machine ? "Answering machine: \(who)" : v.calling ? "Call to \(who)" : "Call from \(who)").font(.headline)
            Text(phase(v)).foregroundStyle(.secondary)
            if v.running {
                HStack(spacing: 16) {
                    Button("Hang up", role: .destructive) { jobs.hangUpVoice() }
                    if v.machine {
                        Button(jobs.listening ? "Stop listening" : "Listen") { jobs.listen(!jobs.listening) }
                        Button("Pick up") { ask { jobs.pickUp() } }
                    } else if v.phase == VoiceJob.Phase.talking {
                        Button(jobs.muted ? "Unmute" : "Mute") { jobs.mute(!jobs.muted) }
                        Button(jobs.loud ? "Earpiece" : "Speaker") { jobs.loudspeaker(!jobs.loud) }
                        Button("Keypad") { keypad.toggle() }
                    }
                }
                .buttonStyle(.borderless)
                if keypad && !v.machine && v.phase == VoiceJob.Phase.talking {
                    Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                        ForEach(["123", "456", "789", "*0#"], id: \.self) { row in
                            GridRow {
                                ForEach(Array(row), id: \.self) { k in
                                    Button(String(k)) { jobs.dtmf(k) }
                                        .font(.title2)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                        .buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
            } else {
                Button("OK") { jobs.dismissVoice() }
            }
        }
    }

    /// what the call is doing, or how it ended
    private func phase(_ v: VoiceJob.State) -> String {
        if !v.running {
            if !v.message.isEmpty { return "Took a message of \(v.messageSeconds) s" }
            if v.result == "" { return v.machine ? "No message left" : "Call ended" }
            return "Stopped: \(v.result ?? "")"
        }
        var s: String
        switch v.phase {
        case .starting: s = "Starting"
        case .dialling: s = "Calling…"
        case .answering: s = "Answering"
        case .talking: s = jobs.muted ? "In a call (muted)" : "In a call"
        case .greeting: s = "Playing the greeting"
        case .recording: s = "Taking a message"
        default: s = "Hanging up"
        }
        if !v.digits.isEmpty { s += " · keys \(v.digits)" }
        return s
    }

    /// a message: from whom, when, how long; played with a tap, shared
    private func messageRow(_ u: URL) -> some View {
        let name = u.deletingPathExtension().lastPathComponent
        let caller = VoiceJob.companion.messageCaller(name: u.lastPathComponent)
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        let when = f.date(from: String(name.prefix(15))).map { DateFormatter.localizedString(from: $0, dateStyle: .short, timeStyle: .short) } ?? ""
        let secs = VoiceFiles.seconds(u)
        return HStack {
            Button {
                player.play(u)
            } label: {
                Image(systemName: player.playing == u ? "stop.fill" : "play.fill")
            }
            .buttonStyle(.borderless)
            VStack(alignment: .leading) {
                Text(caller.isEmpty ? "Unknown caller" : caller)
                Text("\(when) · \(secs / 60):\(String(format: "%02d", secs % 60))").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            ShareLink(item: u) { Image(systemName: "square.and.arrow.up") }.buttonStyle(.borderless)
        }
    }

    /// the messages read from their folder, newest first
    private func reload() {
        messages = AppFolders.files(VoiceFiles.messages).filter { $0.pathExtension.lowercased() == "wav" }
    }
}

/// A message or the greeting played (AVAudioPlayer reads the WAVs as they are)
@MainActor
final class VoicePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var playing: URL?
    private var player: AVAudioPlayer?

    /// [u] played; again, it stops
    func play(_ u: URL) {
        if playing == u {
            stop()
            return
        }
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
        guard let p = try? AVAudioPlayer(contentsOf: u) else { return }
        p.delegate = self
        p.play()
        player = p
        playing = u
    }

    /// what's playing stopped
    func stop() {
        player?.stop()
        player = nil
        playing = nil
    }

    /// its end: nothing playing
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

/// The answering machine's greeting: recorded from the microphone (a minute at most), chosen from a WAV file, or none
@MainActor
final class GreetingRecorder: ObservableObject {
    @Published private(set) var recording = false
    @Published private(set) var seconds = 0
    private let sound = VoiceSound()
    private var samples: [Float] = []
    private var timer: Foundation.Timer?

    /// the greeting's length read (0: none)
    func reload() {
        seconds = FileManager.default.fileExists(atPath: VoiceFiles.greeting.path) ? max(1, VoiceFiles.seconds(VoiceFiles.greeting)) : 0
    }

    /// the microphone recorded (until stop, or a minute)
    func record() {
        guard !recording, sound.start(record: true, loud: true) else { return }
        samples = []
        recording = true
        timer = Foundation.Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.take() }
        }
    }

    /// what the microphone gave since, kept; a minute, and it stops
    private func take() {
        var b = [Float](repeating: 0, count: 8000)
        let n = b.withUnsafeMutableBufferPointer { sound.mic.pop(into: $0.baseAddress!, 8000) }
        samples.append(contentsOf: b[0..<n])
        if samples.count >= 60 * 8000 { stop() }
    }

    /// the recording ended, kept as the greeting if half a second or more
    func stop() {
        guard recording else { return }
        take()
        timer?.invalidate()
        timer = nil
        sound.stop()
        recording = false
        if samples.count >= 4000 { try? VoiceFiles.write(samples, to: VoiceFiles.greeting) }
        samples = []
        reload()
    }

    /// the greeting from the WAV at [u]: nil, or why not
    func choose(_ u: URL) -> String? {
        let scoped = u.startAccessingSecurityScopedResource()
        defer { if scoped { u.stopAccessingSecurityScopedResource() } }
        guard let pcm = VoiceFiles.decode(u) else { return "That file isn't a WAV of PCM, A-law or μ-law" }
        if pcm.size == 0 { return "That file has no sound" }
        let n = min(pcm.size, 60 * 8000)
        do {
            try VoiceFiles.data(Wav.shared.encode(pcm: pcm, n: n)).write(to: VoiceFiles.greeting)
        } catch {
            return "The greeting couldn't be saved"
        }
        reload()
        return nil
    }

    /// no greeting: callers hear the beep alone
    func clear() {
        try? FileManager.default.removeItem(at: VoiceFiles.greeting)
        reload()
    }
}
