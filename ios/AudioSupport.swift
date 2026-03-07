import Foundation
import AVFoundation
import Combine
import CoreGraphics

struct VoiceRecordResult {
    let url: URL
    let durationMs: Int64
}

final class VoiceNoteRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published private(set) var isRecording = false
    @Published private(set) var normalizedLevel: CGFloat = 0
    @Published private(set) var elapsedMs: Int64 = 0

    private var recorder: AVAudioRecorder?
    private var meterTimer: Timer?
    private var outputURL: URL?

    var durationLabel: String {
        let sec = Int(elapsedMs / 1000)
        return String(format: "%02d:%02d", sec / 60, sec % 60)
    }

    func start(completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            completion(startInternal())
        case .denied:
            completion(false)
        case .undetermined:
            session.requestRecordPermission { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else {
                        completion(false)
                        return
                    }
                    completion(granted ? self.startInternal() : false)
                }
            }
        @unknown default:
            completion(false)
        }
    }

    func finish() -> VoiceRecordResult? {
        guard isRecording, let recorder, let url = outputURL else { return nil }
        recorder.stop()
        stopMeterTimer()

        isRecording = false
        let result = VoiceRecordResult(url: url, durationMs: max(300, elapsedMs))
        recorder.delegate = nil
        self.recorder = nil
        outputURL = nil
        elapsedMs = 0
        normalizedLevel = 0
        deactivateSession()
        return result
    }

    func cancel() {
        guard isRecording else { return }
        recorder?.stop()
        stopMeterTimer()

        if let url = outputURL {
            try? FileManager.default.removeItem(at: url)
        }

        recorder?.delegate = nil
        recorder = nil
        outputURL = nil
        isRecording = false
        elapsedMs = 0
        normalizedLevel = 0
        deactivateSession()
    }

    private func startInternal() -> Bool {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true)

            let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("voice_notes", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent("voice_\(UUID().uuidString).m4a")

            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 16_000,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]

            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.delegate = self
            guard recorder.record() else {
                return false
            }

            self.recorder = recorder
            outputURL = url
            isRecording = true
            elapsedMs = 0
            normalizedLevel = 0
            startMeterTimer()
            return true
        } catch {
            return false
        }
    }

    private func startMeterTimer() {
        stopMeterTimer()
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
            guard let self, let recorder = self.recorder, self.isRecording else { return }
            recorder.updateMeters()
            let avg = recorder.averagePower(forChannel: 0)
            let clamped = max(-55, min(0, avg))
            self.normalizedLevel = CGFloat((clamped + 55) / 55)
            self.elapsedMs = Int64(recorder.currentTime * 1000)
        }
    }

    private func stopMeterTimer() {
        meterTimer?.invalidate()
        meterTimer = nil
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

final class VoiceNotePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var playingMessageId: String?
    @Published private(set) var isPlaying = false

    private var player: AVAudioPlayer?

    func togglePlayback(messageID: String, filePath: String) {
        if playingMessageId == messageID {
            stop()
            return
        }

        stop()

        let url = URL(fileURLWithPath: filePath)
        guard FileManager.default.fileExists(atPath: url.path) else { return }

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)

            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.prepareToPlay()
            guard player.play() else { return }

            self.player = player
            playingMessageId = messageID
            isPlaying = true
        } catch {
            stop()
        }
    }

    func stop() {
        player?.stop()
        player?.delegate = nil
        player = nil
        playingMessageId = nil
        isPlaying = false
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        stop()
    }
}
