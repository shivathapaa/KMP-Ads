//
//  AdMobHost.swift
//  Reference implementation of the KMPAdsHost protocol for Google Mobile Ads.
//
//  Copy this file into your app's iOS target. It compiles against any framework name, because the
//  Kotlin protocols keep fixed Swift names; only the `import` of your own framework below changes.
//  Ad unit ids come from the Kotlin AdUnitRegistry, never from this file.
//
//  Setup, once per app:
//
//  1. Xcode → File → Add Package Dependencies…
//     URL: https://github.com/googleads/swift-package-manager-google-mobile-ads.git
//     Add the `GoogleMobileAds` product to your app target.
//
//  2. Info.plist:
//       <key>GADApplicationIdentifier</key>
//       <string>ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY</string>
//       <key>SKAdNetworkItems</key>
//       <array>…</array>   <!-- the current list is in Google's AdMob iOS docs -->
//
//     The app crashes on initialisation without GADApplicationIdentifier.
//
//  3. Add the tracking usage description to both the Debug and the Release configurations:
//       INFOPLIST_KEY_NSUserTrackingUsageDescription = "…";
//
//  4. Pass an instance to Kotlin at startup, for example:
//       MainKt.MainViewController(adsHost: AdMobHost())
//     Pass `nil` to run without ads.
//
//  Written against Google Mobile Ads SDK 13, whose Swift names have no `GAD` prefix
//  (`GADBannerView` is `BannerView`).
//

import AppTrackingTransparency
import Foundation
import GoogleMobileAds
import UIKit
import UserMessagingPlatform

// Replace with your own framework name, e.g. `import ComposeApp`.
import SampleAds

// MARK: -

public final class AdMobHost: NSObject, KMPAdsHost {
    /// Loaded-but-unshown full-screen ads, keyed "format|unitId".
    private var fullScreenAds: [String: FullScreenAdBox] = [:]

    /// Retains banner delegates for the lifetime of their view; UIKit does not.
    private var surfaceDelegates: [ObjectIdentifier: BannerDelegate] = [:]

    /// Retains native ad loader delegates for the lifetime of their view; the SDK does not.
    private var nativeDelegates: [ObjectIdentifier: NativeAdSurfaceDelegate] = [:]

    private var startedOnce = false

    // MARK: Protocol version

    public func protocolVersion() -> Int32 {
        KMPAdsProtocol.shared.VERSION
    }

    // MARK: Lifecycle

    public func start(
        testDeviceIdentifiers: [String],
        ageRestriction: String,
        maxAdContentRating: String,
        callback: any KMPAdsStartCallback
    ) {
        guard !startedOnce else {
            callback.onStarted(canRequestAds: canRequestAds(), adapterSummary: nil)
            return
        }
        startedOnce = true

        let configuration = MobileAds.shared.requestConfiguration
        if !testDeviceIdentifiers.isEmpty {
            configuration.testDeviceIdentifiers = testDeviceIdentifiers
        }
        switch ageRestriction {
        case KMPAdsProtocol.shared.AGE_CHILD:
            configuration.tagForChildDirectedTreatment = true
        case KMPAdsProtocol.shared.AGE_TEEN:
            configuration.tagForUnderAgeOfConsent = true
        default:
            break
        }
        switch maxAdContentRating {
        case KMPAdsProtocol.shared.RATING_G: configuration.maxAdContentRating = .general
        case KMPAdsProtocol.shared.RATING_PG: configuration.maxAdContentRating = .parentalGuidance
        case KMPAdsProtocol.shared.RATING_T: configuration.maxAdContentRating = .teen
        case KMPAdsProtocol.shared.RATING_MA: configuration.maxAdContentRating = .matureAudience
        default: break
        }

        MobileAds.shared.start { status in
            let summary = status.adapterStatusesByClassName
                .map { "\($0.key)=\($0.value.state.rawValue)" }
                .joined(separator: ", ")
            callback.onStarted(canRequestAds: self.canRequestAds(), adapterSummary: summary)
        }
    }

    /// Mutes ad audio. Recommended for apps that play audio.
    public func setAppMuted(muted: Bool) {
        MobileAds.shared.isApplicationMuted = muted
    }

    // MARK: Consent (UMP)

    public func canRequestAds() -> Bool {
        ConsentInformation.shared.canRequestAds
    }

    public func isPrivacyOptionsRequired() -> Bool {
        ConsentInformation.shared.privacyOptionsRequirementStatus == .required
    }

    public func gatherConsent(
        debugGeography: String,
        testDeviceIdentifiers: [String],
        callback: any KMPAdsConsentCallback
    ) {
        let parameters = RequestParameters()

        if debugGeography != KMPAdsProtocol.shared.GEOGRAPHY_DISABLED
            || !testDeviceIdentifiers.isEmpty {
            let debugSettings = DebugSettings()
            debugSettings.testDeviceIdentifiers = testDeviceIdentifiers
            switch debugGeography {
            case KMPAdsProtocol.shared.GEOGRAPHY_EEA: debugSettings.geography = .EEA
            case KMPAdsProtocol.shared.GEOGRAPHY_OTHER: debugSettings.geography = .other
            case KMPAdsProtocol.shared.GEOGRAPHY_REGULATED_US_STATE:
                debugSettings.geography = .regulatedUSState
            default: debugSettings.geography = .disabled
            }
            parameters.debugSettings = debugSettings
        }

        ConsentInformation.shared.requestConsentInfoUpdate(with: parameters) { [weak self] error in
            guard let self else { return }
            if let error {
                // A consent failure leaves canRequestAds false, so no ads are requested.
                self.resolve(callback, error.localizedDescription)
                return
            }
            guard let presenter = Self.topViewController() else {
                self.resolve(callback, "no presenting view controller")
                return
            }
            ConsentForm.loadAndPresentIfRequired(from: presenter) { formError in
                self.resolve(callback, formError?.localizedDescription)
            }
        }
    }

    public func presentPrivacyOptionsForm(callback: any KMPAdsConsentCallback) {
        guard let presenter = Self.topViewController() else {
            resolve(callback, "no presenting view controller")
            return
        }
        ConsentForm.presentPrivacyOptionsForm(from: presenter) { [weak self] error in
            // Consent can be revoked here; Kotlin re-reads canRequestAds afterwards.
            self?.resolve(callback, error?.localizedDescription)
        }
    }

    public func resetConsent() {
        ConsentInformation.shared.reset()
    }

    /// Must run after the consent form and while the app is active; otherwise iOS ignores the
    /// request and the status stays `.notDetermined`.
    public func requestTrackingAuthorization(callback: any KMPAdsConsentCallback) {
        guard #available(iOS 14, *) else {
            resolve(callback, nil)
            return
        }
        guard UIApplication.shared.applicationState == .active else {
            resolve(callback, "app not active; not prompting")
            return
        }
        ATTrackingManager.requestTrackingAuthorization { [weak self] _ in
            // A denial is not a failure: ads still serve, without tracking.
            DispatchQueue.main.async { self?.resolve(callback, nil) }
        }
    }

    private func resolve(_ callback: any KMPAdsConsentCallback, _ errorMessage: String?) {
        callback.onConsentResolved(
            canRequestAds: canRequestAds(),
            privacyOptionsRequired: isPrivacyOptionsRequired(),
            errorMessage: errorMessage
        )
    }

    // MARK: Banner

    /// Synchronous and network-free, so Compose can reserve the height before a request.
    public func adaptiveBannerHeightPoints(widthPoints: Double) -> Double {
        Double(currentOrientationAnchoredAdaptiveBanner(width: CGFloat(widthPoints)).size.height)
    }

    public func makeBanner(
        unitId: String,
        widthPoints: Double,
        callback: any KMPAdsSurfaceCallback
    ) -> UIView {
        let size = currentOrientationAnchoredAdaptiveBanner(width: CGFloat(widthPoints))
        let banner = BannerView(adSize: size)
        banner.adUnitID = unitId
        banner.rootViewController = Self.topViewController()

        let delegate = BannerDelegate(callback: callback, size: size)
        banner.delegate = delegate
        banner.paidEventHandler = { _ in /* revenue is reported through the Android path today */ }
        surfaceDelegates[ObjectIdentifier(banner)] = delegate

        banner.load(Request())
        return banner
    }

    /// Returns an empty `NativeAdView` immediately and fills it when the ad arrives.
    /// `templateId` is currently unused: there is one layout.
    public func makeNative(
        unitId: String,
        templateId: String,
        callback: any KMPAdsSurfaceCallback
    ) -> UIView {
        let adView = NativeAdView()

        let delegate = NativeAdSurfaceDelegate(callback: callback, adView: adView)
        // The SDK does not retain the AdLoader, so the delegate keeps it until disposeSurface.
        let loader = AdLoader(
            adUnitID: unitId,
            rootViewController: Self.topViewController(),
            adTypes: [.native],
            options: nil
        )
        loader.delegate = delegate
        delegate.retain(loader)
        nativeDelegates[ObjectIdentifier(adView)] = delegate

        loader.load(Request())
        return adView
    }

    public func disposeSurface(view: UIView) {
        if let banner = view as? BannerView {
            banner.delegate = nil
            banner.paidEventHandler = nil
            surfaceDelegates.removeValue(forKey: ObjectIdentifier(banner))
        } else if let native = view as? NativeAdView {
            nativeDelegates.removeValue(forKey: ObjectIdentifier(native))?.teardown()
        }
        view.removeFromSuperview()
    }

    // MARK: Full screen

    public func loadFullScreen(
        format: String,
        unitId: String,
        callback: any KMPAdsLoadCallback
    ) {
        let key = Self.key(format, unitId)
        let request = Request()

        func finish(_ box: FullScreenAdBox?, _ error: Error?) {
            if let box {
                self.fullScreenAds[key] = box
                callback.onLoaded(format: format, unitId: unitId)
            } else {
                let nsError = error as NSError?
                callback.onFailedToLoad(
                    format: format,
                    unitId: unitId,
                    code: Int32(nsError?.code ?? -1),
                    message: nsError?.localizedDescription ?? "unknown"
                )
            }
        }

        switch format {
        case KMPAdsProtocol.shared.FORMAT_INTERSTITIAL:
            InterstitialAd.load(with: unitId, request: request) { ad, error in
                finish(ad.map { .interstitial($0) }, error)
            }
        case KMPAdsProtocol.shared.FORMAT_REWARDED:
            RewardedAd.load(with: unitId, request: request) { ad, error in
                finish(ad.map { .rewarded($0) }, error)
            }
        case KMPAdsProtocol.shared.FORMAT_REWARDED_INTERSTITIAL:
            RewardedInterstitialAd.load(with: unitId, request: request) { ad, error in
                finish(ad.map { .rewardedInterstitial($0) }, error)
            }
        case KMPAdsProtocol.shared.FORMAT_APP_OPEN:
            AppOpenAd.load(with: unitId, request: request) { ad, error in
                finish(ad.map { .appOpen($0) }, error)
            }
        default:
            callback.onFailedToLoad(
                format: format, unitId: unitId, code: -1, message: "unknown format"
            )
        }
    }

    public func isFullScreenReady(format: String, unitId: String) -> Bool {
        fullScreenAds[Self.key(format, unitId)] != nil
    }

    public func showFullScreen(
        format: String,
        unitId: String,
        callback: any KMPAdsShowCallback
    ) {
        let key = Self.key(format, unitId)
        guard let box = fullScreenAds.removeValue(forKey: key) else {
            callback.onFailedToShow(
                format: format, unitId: unitId, code: -1, message: "not loaded"
            )
            return
        }
        guard let presenter = Self.topViewController() else {
            callback.onFailedToShow(
                format: format, unitId: unitId, code: -1, message: "no presenting view controller"
            )
            return
        }

        // Retained until a terminal callback fires; the SDK does not hold the delegate for you.
        let delegate = FullScreenDelegate(format: format, unitId: unitId, callback: callback) {
            [weak self] in self?.presentationDelegates.removeValue(forKey: key)
        }
        presentationDelegates[key] = delegate
        box.present(from: presenter, delegate: delegate, callback: callback,
                    format: format, unitId: unitId)
    }

    public func discardFullScreen(format: String, unitId: String) {
        fullScreenAds.removeValue(forKey: Self.key(format, unitId))
    }

    private var presentationDelegates: [String: FullScreenDelegate] = [:]

    private static func key(_ format: String, _ unitId: String) -> String { "\(format)|\(unitId)" }

    // MARK: Presenting controller

    /// The topmost view controller of the foreground window scene.
    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

// MARK: - Boxed ads

private enum FullScreenAdBox {
    case interstitial(InterstitialAd)
    case rewarded(RewardedAd)
    case rewardedInterstitial(RewardedInterstitialAd)
    case appOpen(AppOpenAd)

    func present(
        from presenter: UIViewController,
        delegate: FullScreenDelegate,
        callback: any KMPAdsShowCallback,
        format: String,
        unitId: String
    ) {
        switch self {
        case .interstitial(let ad):
            ad.fullScreenContentDelegate = delegate
            ad.present(from: presenter)
        case .appOpen(let ad):
            ad.fullScreenContentDelegate = delegate
            ad.present(from: presenter)
        case .rewarded(let ad):
            ad.fullScreenContentDelegate = delegate
            ad.present(from: presenter) {
                let reward = ad.adReward
                callback.onUserEarnedReward(
                    format: format,
                    unitId: unitId,
                    rewardType: reward.type,
                    amount: Int32(truncating: reward.amount)
                )
            }
        case .rewardedInterstitial(let ad):
            ad.fullScreenContentDelegate = delegate
            ad.present(from: presenter) {
                let reward = ad.adReward
                callback.onUserEarnedReward(
                    format: format,
                    unitId: unitId,
                    rewardType: reward.type,
                    amount: Int32(truncating: reward.amount)
                )
            }
        }
    }
}

// MARK: - Delegates

private final class FullScreenDelegate: NSObject, FullScreenContentDelegate {
    private let format: String
    private let unitId: String
    private let callback: any KMPAdsShowCallback
    private let onTerminal: () -> Void

    init(
        format: String,
        unitId: String,
        callback: any KMPAdsShowCallback,
        onTerminal: @escaping () -> Void
    ) {
        self.format = format
        self.unitId = unitId
        self.callback = callback
        self.onTerminal = onTerminal
    }

    func adDidRecordImpression(_ ad: FullScreenPresentingAd) {
        callback.onImpression(format: format, unitId: unitId)
    }

    func adDidRecordClick(_ ad: FullScreenPresentingAd) {
        callback.onClick(format: format, unitId: unitId)
    }

    func adWillPresentFullScreenContent(_ ad: FullScreenPresentingAd) {
        callback.onShown(format: format, unitId: unitId)
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        callback.onDismissed(format: format, unitId: unitId)
        onTerminal()
    }

    func ad(
        _ ad: FullScreenPresentingAd,
        didFailToPresentFullScreenContentWithError error: Error
    ) {
        let nsError = error as NSError
        callback.onFailedToShow(
            format: format,
            unitId: unitId,
            code: Int32(nsError.code),
            message: nsError.localizedDescription
        )
        onTerminal()
    }
}

private final class BannerDelegate: NSObject, BannerViewDelegate {
    private let callback: any KMPAdsSurfaceCallback
    private let size: AdSize

    init(callback: any KMPAdsSurfaceCallback, size: AdSize) {
        self.callback = callback
        self.size = size
    }

    func bannerViewDidReceiveAd(_ bannerView: BannerView) {
        callback.onSurfaceLoaded(
            widthPoints: Double(size.size.width),
            heightPoints: Double(size.size.height)
        )
    }

    func bannerView(_ bannerView: BannerView, didFailToReceiveAdWithError error: Error) {
        let nsError = error as NSError
        callback.onSurfaceFailed(
            code: Int32(nsError.code),
            message: nsError.localizedDescription
        )
    }

    func bannerViewDidRecordImpression(_ bannerView: BannerView) {
        callback.onSurfaceImpression()
    }

    func bannerViewDidRecordClick(_ bannerView: BannerView) {
        callback.onSurfaceClick()
    }
}

// MARK: - Native

/// Loads one native ad and binds it into its `NativeAdView`.
///
/// Asset views are registered before `adView.nativeAd` is set, which is what counts clicks and
/// impressions. The "Ad" badge sits at the leading edge, clear of the AdChoices overlay.
private final class NativeAdSurfaceDelegate: NSObject, NativeAdLoaderDelegate, NativeAdDelegate {
    private let callback: any KMPAdsSurfaceCallback
    private weak var adView: NativeAdView?
    private var loader: AdLoader?
    private var nativeAd: NativeAd?

    init(callback: any KMPAdsSurfaceCallback, adView: NativeAdView) {
        self.callback = callback
        self.adView = adView
    }

    func retain(_ loader: AdLoader) { self.loader = loader }

    func adLoader(_ adLoader: AdLoader, didReceive nativeAd: NativeAd) {
        guard let adView else { return }
        self.nativeAd = nativeAd
        nativeAd.delegate = self
        Self.bind(nativeAd, into: adView)
        // Set after registering the asset views, so clicks and impressions are counted.
        adView.nativeAd = nativeAd

        let fitted = adView.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
        callback.onSurfaceLoaded(
            widthPoints: Double(fitted.width),
            heightPoints: Double(fitted.height)
        )
    }

    func adLoader(_ adLoader: AdLoader, didFailToReceiveAdWithError error: Error) {
        let nsError = error as NSError
        callback.onSurfaceFailed(code: Int32(nsError.code), message: nsError.localizedDescription)
    }

    func nativeAdDidRecordImpression(_ nativeAd: NativeAd) { callback.onSurfaceImpression() }
    func nativeAdDidRecordClick(_ nativeAd: NativeAd) { callback.onSurfaceClick() }

    /// Called from `disposeSurface`; drops the delegate and every SDK object.
    func teardown() {
        loader?.delegate = nil
        nativeAd?.delegate = nil
        loader = nil
        nativeAd = nil
    }

    private static func bind(_ nativeAd: NativeAd, into adView: NativeAdView) {
        adView.subviews.forEach { $0.removeFromSuperview() }

        let headline = UILabel()
        headline.font = .boldSystemFont(ofSize: 16)
        headline.numberOfLines = 2
        headline.text = nativeAd.headline

        let advertiser = UILabel()
        advertiser.font = .systemFont(ofSize: 13)
        advertiser.textColor = .secondaryLabel
        advertiser.text = nativeAd.advertiser
        advertiser.isHidden = nativeAd.advertiser == nil

        let icon = UIImageView()
        icon.image = nativeAd.icon?.image
        icon.isHidden = nativeAd.icon == nil
        icon.contentMode = .scaleAspectFit
        icon.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            icon.widthAnchor.constraint(equalToConstant: 40),
            icon.heightAnchor.constraint(equalToConstant: 40),
        ])

        // The "Ad" attribution label, at the leading edge so it never overlaps AdChoices.
        let adBadge = UILabel()
        adBadge.text = " Ad "
        adBadge.font = .systemFont(ofSize: 10)
        adBadge.textColor = .white
        adBadge.backgroundColor = .systemBlue
        adBadge.layer.cornerRadius = 3
        adBadge.clipsToBounds = true
        adBadge.setContentHuggingPriority(.required, for: .horizontal)

        let titleColumn = UIStackView(arrangedSubviews: [headline, advertiser])
        titleColumn.axis = .vertical

        let header = UIStackView(arrangedSubviews: [adBadge, icon, titleColumn])
        header.axis = .horizontal
        header.spacing = 8
        header.alignment = .center

        let body = UILabel()
        body.font = .systemFont(ofSize: 13)
        body.textColor = .secondaryLabel
        body.numberOfLines = 3
        body.text = nativeAd.body
        body.isHidden = nativeAd.body == nil

        let media = MediaView()
        media.mediaContent = nativeAd.mediaContent
        media.translatesAutoresizingMaskIntoConstraints = false
        media.heightAnchor.constraint(greaterThanOrEqualToConstant: 120).isActive = true
        media.setContentHuggingPriority(.defaultLow, for: .vertical)

        let cta = UIButton(type: .system)
        cta.setTitle(nativeAd.callToAction, for: .normal)
        cta.setTitleColor(.white, for: .normal)
        cta.backgroundColor = .systemBlue
        cta.layer.cornerRadius = 8
        // The NativeAdView routes the tap; the button must not intercept it.
        cta.isUserInteractionEnabled = false
        cta.isHidden = nativeAd.callToAction == nil

        let stack = UIStackView(arrangedSubviews: [header, body, media, cta])
        stack.axis = .vertical
        stack.spacing = 8
        stack.isLayoutMarginsRelativeArrangement = true
        stack.layoutMargins = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        stack.translatesAutoresizingMaskIntoConstraints = false

        adView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: adView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: adView.trailingAnchor),
            stack.topAnchor.constraint(equalTo: adView.topAnchor),
            stack.bottomAnchor.constraint(equalTo: adView.bottomAnchor),
        ])

        // Register asset views. Must happen before `adView.nativeAd` is set.
        adView.headlineView = headline
        adView.advertiserView = advertiser
        adView.iconView = icon
        adView.bodyView = body
        adView.mediaView = media
        adView.callToActionView = cta
    }
}
