import SwiftUI
import SampleAds

@main
struct iOSApp: App {
    /// Created once for the process. Pass `nil` instead of `AdMobHost()` to run without ads.
    @StateObject private var ads = AdsHolder()

    var body: some Scene {
        WindowGroup {
            ComposeView(entryPoint: ads.entryPoint)
                .ignoresSafeArea(.keyboard)
                .onAppear {
                    // Consent first, then tracking authorization. iOS ignores a
                    // tracking prompt shown during launch or over the consent form.
                    ads.entryPoint.startConsentFlow()
                }
                // Sessions follow the app entering and leaving the background. Not scenePhase
                // .active, which also fires after a system alert, Control Center or the app
                // switcher, none of which leave the app.
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: UIApplication.willEnterForegroundNotification
                    )
                ) { _ in
                    ads.entryPoint.onAppForegrounded()
                    // Retries a consent gather that failed, for example offline. A completed one
                    // is not repeated.
                    ads.entryPoint.startConsentFlow()
                }
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: UIApplication.didEnterBackgroundNotification
                    )
                ) { _ in
                    ads.entryPoint.onAppBackgrounded()
                }
        }
    }
}

private final class AdsHolder: ObservableObject {
    let entryPoint = SampleIosEntryPoint(host: AdMobHost())
}

private struct ComposeView: UIViewControllerRepresentable {
    let entryPoint: SampleIosEntryPoint

    func makeUIViewController(context: Context) -> UIViewController {
        entryPoint.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
