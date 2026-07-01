import AVFoundation
import Foundation

/// 首次启动申请相机 + 麦克风运行时权限。
///
/// 背景:iOS 首次调用 `AVCaptureSession.startRunning` 前若未获授权,`AVCaptureDevice.default(for:)`
/// 返回 nil 或 session 静默失败(相机黑屏)。Info.plist 里的 `NSCameraUsageDescription` /
/// `NSMicrophoneUsageDescription` 只是弹窗文案,不会主动触发权限申请。
///
/// 用法:在 `iOSApp` 的 App 生命周期 init / onAppear 里调一次 [requestAll]。
/// 权限拒绝时**不崩溃**、不重试 —— 让 Compose UI 显示黑屏,用户去系统"设置"改后重启即可。
///
/// Ref:
///   - https://developer.apple.com/documentation/avfoundation/capture_setup/requesting_authorization_for_media_capture
final class PermissionManager {

    static let shared = PermissionManager()

    private init() {}

    /// 相机 + 麦克风权限一次性申请。串行(相机先、麦克风后),用户看到两个弹窗。
    /// - Parameter completion: 主线程回调,`(cameraGranted, micGranted)`。可选。
    func requestCameraAndMicrophonePermissions(
        completion: ((_ cameraGranted: Bool, _ micGranted: Bool) -> Void)? = nil
    ) {
        requestCameraAccess { cameraGranted in
            self.requestMicrophoneAccess { micGranted in
                DispatchQueue.main.async {
                    completion?(cameraGranted, micGranted)
                }
            }
        }
    }

    // MARK: - 相机

    private func requestCameraAccess(completion: @escaping (Bool) -> Void) {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            completion(true)
        case .notDetermined:
            // 首次弹权限对话框(用 Info.plist 里的 NSCameraUsageDescription 文案)
            AVCaptureDevice.requestAccess(for: .video) { granted in
                completion(granted)
            }
        case .denied, .restricted:
            // 用户此前拒绝或家长控制 — 不再骚扰,静默失败
            completion(false)
        @unknown default:
            completion(false)
        }
    }

    // MARK: - 麦克风

    private func requestMicrophoneAccess(completion: @escaping (Bool) -> Void) {
        // iOS 17+ 用 AVAudioApplication.requestRecordPermission;15/16 用 AVAudioSession
        // 项目 deployment target 是 15.0,统一走 AVAudioSession 路径(iOS 17 仍兼容)
        let session = AVAudioSession.sharedInstance()
        // recordPermission 只读,不主动申请
        switch session.recordPermission {
        case .granted:
            completion(true)
        case .undetermined:
            session.requestRecordPermission { granted in
                completion(granted)
            }
        case .denied:
            completion(false)
        @unknown default:
            completion(false)
        }
    }
}
