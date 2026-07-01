import SwiftUI
import ComposeApp

/// Container view for the KMP Compose UI.
///
/// Design decision (iOS safe area handling):
///   - SwiftUI is fully responsible for safe area (top notch + bottom
///     Home Indicator). We DON'T ignoresSafeArea, so Compose lives inside
///     the safe region. Bottom bar sits right above the Home Indicator with
///     no "floating" gap.
///   - The Home Indicator background is the app's global background color
///     (UvpColor.Bg) — set via SwiftUI's back-plate view below.
///   - Keyboard: pushes content up as usual (default).
///
/// This gives the natural iOS look where the tab bar hugs the Home Indicator
/// line, matching Apple's HIG default.
struct ContentView: View {
    var body: some View {
        ZStack(alignment: .bottom) {
            // Back-plate paints under the Home Indicator so its background
            // matches the Compose bottom bar (surface white).
            Color.white
                .ignoresSafeArea(.container, edges: .bottom)
            ComposeView()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
