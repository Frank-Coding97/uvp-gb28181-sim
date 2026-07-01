import SwiftUI
import ComposeApp
// Shared 是通过 ComposeApp export 传递过来的,不需要单独 import

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
