
import Foundation
import Capacitor
import AVFoundation

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(VideoCompressorPlugin)
public class VideoCompressorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "VideoCompressorPlugin"
    public let jsName = "VideoCompressor"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "echo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "compressVideo", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = VideoCompressor()

    @objc func echo(_ call: CAPPluginCall) {
        let value = call.getString("value") ?? ""
        call.resolve([
            "value": implementation.echo(value)
        ])
    }

    // The core plugin method called from the web view.
    @objc func compressVideo(_ call: CAPPluginCall) {
        guard let path = call.getString("path") else {
            call.reject("Must provide a 'path' to the video file.")
            return
        }

        let sourceURL = URL(fileURLWithPath: path)
        let asset = AVAsset(url: sourceURL)

        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString).appendingPathExtension("mp4")

        // Determine the export preset based on the requested quality
        let quality = call.getString("quality", "high")
        let preset: String

        switch quality {
        case "low":
            preset = AVAssetExportPresetLowQuality
        case "medium":
            preset = AVAssetExportPresetMediumQuality
        case "high":
            preset = AVAssetExportPresetHighestQuality
        default:
            preset = AVAssetExportPresetHighestQuality
        }

        guard let exportSession = AVAssetExportSession(asset: asset, presetName: preset) else {
            call.reject("Failed to create export session.")
            return
        }

        exportSession.outputURL = tempURL
        exportSession.outputFileType = .mp4

        // Use a timer to periodically check for progress updates and emit them
        let progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let progress = Int(exportSession.progress * 100)
            self.notifyListeners("videoProgress", data: ["progress": progress])
        }

        exportSession.exportAsynchronously {
            // Invalidate the timer once the export is complete
            progressTimer.invalidate()

            DispatchQueue.main.async {
                switch exportSession.status {
                case .completed:
                    do {
                        // Compression successful, now overwrite the original file
                        if FileManager.default.fileExists(atPath: sourceURL.path) {
                            try FileManager.default.removeItem(at: sourceURL)
                        }

                        try FileManager.default.moveItem(at: tempURL, to: sourceURL)

                        call.resolve()
                    } catch let error {
                        call.reject("Failed to overwrite file: \(error.localizedDescription)", "FILE_OVERWRITE_ERROR")
                    }
                case .failed:
                    if let error = exportSession.error {
                        call.reject("Video compression failed: \(error.localizedDescription)", "COMPRESSION_FAILED")
                    } else {
                        call.reject("Video compression failed with unknown error.")
                    }
                case .cancelled:
                    call.reject("Video compression was cancelled.")
                default:
                    break
                }
            }
        }
    }
}
