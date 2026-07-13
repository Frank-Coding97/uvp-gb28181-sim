import SwiftUI
import ComposeApp

@main
struct iOSApp: App {

    init() {
        // 首次启动申请相机 + 麦克风权限,Info.plist 里的 usage description 只是弹窗文案,
        // 必须显式调 requestAccess 才会弹权限对话框。
        // 权限拒绝不崩溃、不重试 — Compose 相机预览会黑屏,用户去系统设置改。
        PermissionManager.shared.requestCameraAndMicrophonePermissions()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
