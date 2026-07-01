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
struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.container, edges: .bottom)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
