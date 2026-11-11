// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import AVFoundation
import DModemShared

/// ANSI music played as a PC speaker did: each note a square wave, queued
/// behind what's playing so the screen goes on meanwhile.
final class MusicPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let rate = 22050.0
    private lazy var format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)
    private var started = false

    /// the engine running, the first time there's music
    private func start() -> Bool {
        if started { return true }
        guard let format else { return false }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { return false }
        started = true
        return true
    }

    /// [notes] played after what's playing
    func play(_ notes: [MusicNote]) {
        guard !notes.isEmpty, start(), let format else { return }
        let frames = notes.reduce(0) { $0 + Int(rate) * Int($1.ms + $1.restMs) / 1000 }
        guard frames > 0, let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let out = buf.floatChannelData?[0] else { return }
        buf.frameLength = AVAudioFrameCount(frames)
        var i = 0
        for n in notes {
            let on = Int(rate) * Int(n.ms) / 1000
            let off = Int(rate) * Int(n.restMs) / 1000
            let half = n.hz > 0 ? rate / n.hz / 2 : 0
            for k in 0..<on where i < frames {
                // (a quarter of full scale: a speaker's beep, not a blast)
                out[i] = half > 0 ? (Int(Double(k) / half) % 2 == 0 ? 0.25 : -0.25) : 0
                i += 1
            }
            for _ in 0..<off where i < frames {
                out[i] = 0
                i += 1
            }
        }
        node.scheduleBuffer(buf, completionHandler: nil)
        if !node.isPlaying { node.play() }
    }

    /// the music stopped and what's queued dropped
    func stop() {
        node.stop()
    }
}
