import SwiftUI
import SampleAds

@main
struct iOSApp: App {
    /// Created once for the process. Pass `nil` instead of `AdMobHost()` to run without ads.
    @StateObject private var ads = AdsHolder()

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ComposeView(entryPoint: ads.entryPoint)
                .ignoresSafeArea(.keyboard)
                .onAppear {
                    // Consent first, then tracking authorization. iOS ignores a
                    // tracking prompt shown during launch or over the consent form.
                    ads.entryPoint.startConsentFlow()
                }
        }
        // The single-argument onChange keeps the sample compatible with iOS 16.
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: ads.entryPoint.onAppForegrounded()
            case .background: ads.entryPoint.onAppBackgrounded()
            default: break
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
