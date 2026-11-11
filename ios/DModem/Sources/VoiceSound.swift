// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import AVFoundation
import DModemShared
import Foundation

/// Samples (as floats) from one thread to another: the oldest dropped
/// when it's full.
final class SampleQueue: @unchecked Sendable {
    private var buf: [Float]
    private var head = 0
    private var count = 0
    private let lock = NSLock()

    /// room for [capacity] samples
    init(capacity: Int) { buf = [Float](repeating: 0, count: capacity) }

    /// [x] put in
    func push(_ x: UnsafeBufferPointer<Float>) {
        lock.lock(); defer { lock.unlock() }
        for v in x {
            if count == buf.count {
                head = (head + 1) % buf.count
                count -= 1
            }
            buf[(head + count) % buf.count] = v
            count += 1
        }
    }

    /// up to [n] into [p]: how many
    func pop(into p: UnsafeMutablePointer<Float>, _ n: Int) -> Int {
        lock.lock(); defer { lock.unlock() }
        let k = min(n, count)
        for i in 0..<k {
            p[i] = buf[head]
            head = (head + 1) % buf.count
        }
        count -= k
        return k
    }

    var available: Int {
        lock.lock(); defer { lock.unlock() }
        return count
    }

    /// emptied
    func clear() {
        lock.lock(); defer { lock.unlock() }
        head = 0
        count = 0
    }
}

/// The phone's speaker and microphone at a voice call's 8000 Hz: the
/// speaker played from [speaker], the microphone (converted) into [mic].
/// The audio session in its voice chat mode (the phone's echo
/// cancelling) while it runs, back to the modem's playback after.
final class VoiceSound: @unchecked Sendable {
    let speaker = SampleQueue(capacity: 8000)
    let mic = SampleQueue(capacity: 8000)
    private var engine: AVAudioEngine?
    private var tapped = false
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false)!

    var running: Bool { engine != nil }

    /// the speaker on, and the microphone if [record]; [loud]: the loudspeaker (else the earpiece). False if there's no sound
    func start(record: Bool, loud: Bool) -> Bool {
        stop()
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playAndRecord, mode: .voiceChat, options: loud ? [.defaultToSpeaker, .allowBluetooth] : [.allowBluetooth])
            try s.setActive(true)
        } catch {
            NSLog("voice sound: \(error)")
        }
        let e = AVAudioEngine()
        let fmt = format
        let q = speaker
        let src = AVAudioSourceNode(format: fmt) { _, _, frames, abl -> OSStatus in
            let list = UnsafeMutableAudioBufferListPointer(abl)
            guard let p = list[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let n = Int(frames)
            var i = q.pop(into: p, n)
            while i < n { p[i] = 0; i += 1 }
            return noErr
        }
        e.attach(src)
        e.connect(src, to: e.mainMixerNode, format: fmt)
        if record {
            let input = e.inputNode
            let hw = input.outputFormat(forBus: 0)
            if hw.sampleRate > 0, hw.channelCount > 0, let conv = AVAudioConverter(from: hw, to: fmt) {
                let m = mic
                input.installTap(onBus: 0, bufferSize: 1024, format: hw) { buffer, _ in
                    let cap = AVAudioFrameCount(Double(buffer.frameLength) * fmt.sampleRate / hw.sampleRate) + 32
                    guard let out = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: cap) else { return }
                    var given = false
                    var err: NSError?
                    _ = conv.convert(to: out, error: &err) { _, status in
                        if given { status.pointee = .noDataNow; return nil }
                        given = true
                        status.pointee = .haveData
                        return buffer
                    }
                    if let d = out.floatChannelData?[0], out.frameLength > 0 {
                        m.push(UnsafeBufferPointer(start: d, count: Int(out.frameLength)))
                    }
                }
                tapped = true
            }
        }
        do {
            e.prepare()
            try e.start()
        } catch {
            if tapped { e.inputNode.removeTap(onBus: 0) }
            tapped = false
            NSLog("voice sound: \(error)")
            return false
        }
        engine = e
        return true
    }

    /// the loudspeaker (or the earpiece) for the call's sound
    func loudspeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    /// stopped; the session back to the modem's playback
    func stop() {
        guard let e = engine else { return }
        if tapped { e.inputNode.removeTap(onBus: 0) }
        e.stop()
        tapped = false
        engine = nil
        speaker.clear()
        mic.clear()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
    }
}

/// A voice call's sound moved between its job and the phone every 20 ms
/// (the job's Kotlin kept off the audio threads): the far end's samples
/// to the speaker, the microphone's to the line.
final class VoicePump: @unchecked Sendable {
    private let job: VoiceJob
    private let sound: VoiceSound
    private var timer: DispatchSourceTimer?
    private let pcm = KotlinShortArray(size: 1600)
    private var floats = [Float](repeating: 0, count: 1600)
    private let queue = DispatchQueue(label: "voice-pump")

    /// [job]'s sound to and from [sound]
    init(job: VoiceJob, sound: VoiceSound) {
        self.job = job
        self.sound = sound
    }

    /// the pump started, every 20 ms
    func start() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now(), repeating: .milliseconds(20))
        t.setEventHandler { [weak self] in self?.pump() }
        t.resume()
        timer = t
    }

    /// the pump stopped
    func stop() {
        timer?.cancel()
        timer = nil
    }

    /// the far end's samples to the speaker's queue, the microphone's to the job
    private func pump() {
        // the far end's (no more than a quarter second behind)
        if sound.speaker.available > 2000 { sound.speaker.clear() }
        let n = Int(job.read(pcm: pcm, off: 0, len: 1600))
        if n > 0 {
            for i in 0..<n { floats[i] = Float(pcm.get(index: Int32(i))) / 32768 }
            floats.withUnsafeBufferPointer { sound.speaker.push(UnsafeBufferPointer(rebasing: $0[0..<n])) }
        }
        // the microphone's
        let m = floats.withUnsafeMutableBufferPointer { sound.mic.pop(into: $0.baseAddress!, 1600) }
        if m > 0 {
            for i in 0..<m { pcm.set(index: Int32(i), value: Int16(max(-32768, min(32767, floats[i] * 32767)))) }
            job.write(pcm: pcm, off: 0, len: Int32(m))
        }
    }
}

/// WAV files and Kotlin's arrays from Swift
enum VoiceFiles {
    /// [d]'s octets as Kotlin's
    static func bytes(_ d: Data) -> KotlinByteArray {
        let b = KotlinByteArray(size: Int32(d.count))
        for (i, x) in d.enumerated() { b.set(index: Int32(i), value: Int8(bitPattern: x)) }
        return b
    }

    /// Kotlin's octets as Data
    static func data(_ b: KotlinByteArray) -> Data {
        var d = Data(count: Int(b.size))
        for i in 0..<Int(b.size) { d[i] = UInt8(bitPattern: b.get(index: Int32(i))) }
        return d
    }

    /// a sound file's samples at 8000 Hz, one channel: WAV, AIFF and .au
    /// read by the shared code, the rest (MP3, AAC/M4A, FLAC, ALAC, CAF)
    /// by the phone's decoders (nil: not one either reads)
    static func decode(_ url: URL) -> KotlinShortArray? {
        guard let d = try? Data(contentsOf: url) else { return nil }
        if let pcm = Wav.shared.decode(data: bytes(d)) { return pcm }
        guard let s = bySystem(url) else { return nil }
        let pcm = KotlinShortArray(size: Int32(s.count))
        for (i, v) in s.enumerated() { pcm.set(index: Int32(i), value: v) }
        return pcm
    }

    /// a sound file the phone decodes, at 8000 Hz, one channel (five
    /// minutes at most); nil if it can't
    static func bySystem(_ url: URL, maxSeconds: Int = 300) -> [Int16]? {
        guard let f = try? AVAudioFile(forReading: url) else { return nil }
        let src = f.processingFormat
        guard src.sampleRate > 0, src.channelCount > 0,
              let dst = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false),
              let conv = AVAudioConverter(from: src, to: dst),
              let inBuf = AVAudioPCMBuffer(pcmFormat: src, frameCapacity: 4096),
              let outBuf = AVAudioPCMBuffer(pcmFormat: dst, frameCapacity: AVAudioFrameCount(4096 * 8000 / src.sampleRate) + 64)
        else { return nil }
        // (the channels mixed, not the first alone)
        conv.downmix = true
        var out: [Int16] = []
        var ended = false
        while out.count < maxSeconds * 8000 {
            outBuf.frameLength = 0
            var err: NSError?
            let st = conv.convert(to: outBuf, error: &err) { _, status in
                if !ended {
                    do { try f.read(into: inBuf) } catch { inBuf.frameLength = 0 }
                    ended = inBuf.frameLength == 0
                }
                if ended {
                    status.pointee = .endOfStream
                    return nil
                }
                status.pointee = .haveData
                return inBuf
            }
            if let x = outBuf.floatChannelData?[0] {
                for i in 0..<Int(outBuf.frameLength) {
                    out.append(Int16(max(-32768, min(32767, x[i] * 32767))))
                }
            }
            if st == .error || st == .endOfStream { break }
        }
        return out.isEmpty ? nil : out
    }

    /// [samples] (floats at 8000 Hz) written to [url] as a WAV
    static func write(_ samples: [Float], to url: URL) throws {
        let pcm = KotlinShortArray(size: Int32(samples.count))
        for (i, x) in samples.enumerated() { pcm.set(index: Int32(i), value: Int16(max(-32768, min(32767, x * 32767)))) }
        try data(Wav.shared.encode(pcm: pcm, n: Int32(samples.count))).write(to: url)
    }

    /// the folder of the answering machine's messages: Documents/Voicemail (the Files app shows it)
    static var messages: URL {
        let u = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Voicemail", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// the greeting's file (Application Support)
    static var greeting: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u.appendingPathComponent("voicemail-greeting.wav")
    }

    /// a WAV's length (s), from its size
    static func seconds(_ url: URL) -> Int {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return Int(Wav.shared.seconds(fileSize: Int64(size)))
    }
}
