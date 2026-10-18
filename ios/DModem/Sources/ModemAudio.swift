// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import AVFoundation
import Foundation

/// The modem's sound on the phone: its speaker (ATM, ATL: the dialling and
/// the handshake), and while the modem runs, the audio session that keeps
/// the app running in the background (UIBackgroundModes audio), quiet
/// between the speaker's sounds.
final class ModemAudio: @unchecked Sendable {
    /// the speaker's sound played (the settings' "speaker")
    var enabled = true

    private let lock = NSLock()
    private let engine = AVAudioEngine()
    /// the speaker's sound, and (on its own, so the sound never waits behind it) the quiet
    private let player = AVAudioPlayerNode()
    private let quiet = AVAudioPlayerNode()
    /// the modem's samples: 16 bit at 8000 Hz, as floats for the player
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false)!
    private var running = false
    private var gain: Float = 0

    init() {
        engine.attach(player)
        engine.attach(quiet)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.connect(quiet, to: engine.mainMixerNode, format: format)
    }

    /// the session active, the engine playing (quiet): the app keeps running behind others
    func begin() {
        lock.lock(); defer { lock.unlock() }
        if running { return }
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try s.setActive(true)
            try engine.start()
            player.play()
            quiet.play()
            running = true
            scheduleQuiet()
        } catch {
            NSLog("modem audio: \(error)")
        }
    }

    /// the session given back (CallKit's call activated it): the engine playing again
    func resume() {
        lock.lock(); defer { lock.unlock() }
        guard running, !engine.isRunning else { return }
        do {
            try engine.start()
            // (the quiet re-queued itself as the engine stopped: it plays on)
            player.play()
            quiet.play()
        } catch {
            NSLog("modem audio: \(error)")
        }
    }

    func end() {
        lock.lock(); defer { lock.unlock() }
        if !running { return }
        running = false
        player.stop()
        quiet.stop()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// the speaker's level (0 off, 1...3: ATL's quiet, medium, loud)
    func speaker(_ level: Int) {
        lock.lock(); defer { lock.unlock() }
        gain = !enabled || level <= 0 ? 0 : [0.3, 0.6, 1.0][min(level, 3) - 1]
    }

    /// the line's sound while the speaker is on
    func play(_ pcm: UnsafeBufferPointer<Int16>) {
        lock.lock()
        let g = gain
        let on = running
        lock.unlock()
        guard on, g > 0, pcm.count > 0,
              let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(pcm.count)) else { return }
        buf.frameLength = AVAudioFrameCount(pcm.count)
        let out = buf.floatChannelData![0]
        for i in 0..<pcm.count { out[i] = Float(pcm[i]) / 32768 * g }
        player.scheduleBuffer(buf)
    }

    /// a second of quiet, again and again: the session stays active with nothing to play
    private func scheduleQuiet() {
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8000) else { return }
        buf.frameLength = 8000
        quiet.scheduleBuffer(buf) { [weak self] in
            guard let self else { return }
            self.lock.lock(); let on = self.running; self.lock.unlock()
            if on { self.scheduleQuiet() }
        }
    }
}
