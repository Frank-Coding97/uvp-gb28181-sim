import SwiftUI
import ComposeApp

/// Container view for the KMP Compose UI.
///
/// Design decision (iOS 26 Liquid Glass floating tab bar):
///   - Top: SwiftUI applies status-bar safe area — content starts below the
///     notch / Dynamic Island. This matches iOS HIG.
///   - Bottom: Compose extends BEHIND the Home Indicator via
///     .ignoresSafeArea(.container, edges: .bottom). This lets the floating
///     tab bar (in Compose) sit right above the Home Indicator with proper
///     8dp respiration, no big white gap.
///   - The floating tab bar itself handles its own safe-area inset via
///     WindowInsets.safeDrawing.only(Bottom), and paints its blur / capsule
///     background so content underneath is visible with a frosted glass look.
///   - Keyboard: opt out of SwiftUI's automatic keyboard avoidance so the
///     whole Compose view isn't shifted up when a text field gains focus
///     (which used to push the floating tab bar to the top of the screen).
///     Keyboard insets are consumed inside Compose via WindowInsets.ime /
///     Modifier.imePadding() — see App.kt / MainViewController.kt.
struct ContentView: View {
    var body: some View {
        // Color.white 铺全屏防蓝色透出 - iOS 冷启动系统 splash 撤下到 Compose
        // 品牌屏首帧之间, SwiftUI WindowGroup 默认容器可能显示 systemBackground
        // 或透明, 视觉上会闪一下"AppIcon fallback 蓝底"。显式铺白让整条链路
        // 白底一致, 消除观感"蓝色一闪"。
        ZStack {
            Color.white.ignoresSafeArea()
            ComposeView()
                .ignoresSafeArea(.container, edges: .bottom)
                .ignoresSafeArea(.keyboard)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
