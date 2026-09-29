# Ad policy

Store and ad network policies, and how kmp-ads helps you follow them. Breaking them can get an ad
account suspended or an app rejected.

## Invalid traffic

AdMob enforces invalid traffic per account: a disabled account takes down every app on it and
forfeits unpaid earnings. A CI job or screenshot test that loads a live ad unit is exactly the pattern
it looks for.

kmp-ads has three independent safeguards:

1. `AdUnitMode.ForceTestUnits` never reads the live unit table. `AdUnitId.live()` also rejects
   Google's demo publisher, so a demo unit registered as live fails at configuration time.
2. With `AdsConfig.hostDeclaresDebugBuild` set, asking for live units is reported as
   `AdConfigProblem.LiveUnitsInDebugBuild`.
3. Live ids stay out of source code: load them from remote config or `BuildConfig`.

Screenshot tests, previews and any build without a platform adapter get `NoOpAdsSystem`: nothing
composes, no platform view is created and no request is made.

## Placement rules

Some of these are enforced by the policy engine; the rest are for you to follow in your layout.

| # | Rule |
|---|---|
| P-1 | Never show a full-screen or sound-capable ad while audio is playing or buffering. Declare a signal for it and list it in the placement's `suppressWhen`. |
| P-2 | Set `muteAds = true` in any app that plays audio. |
| P-3 | Show interstitials only at natural transitions: never on launch, never on exit, never in the middle of a task. |
| P-4 | Cap the frequency of every full-screen format. Start with `AdPolicy.FullScreenDefault`. |
| P-5 | Show app-open ads only when the user returns to the app: never on first launch, and never when returning from a flow the app started (a picker, share sheet, paywall, consent form or tracking prompt). At most one per foreground, at least four hours apart. |
| P-6 | Keep at least 8dp of non-interactive space between an ad and any tap target, and never place an ad between two interactive strips. |
| P-7 | Show no ads on a screen without publisher content: empty, loading, error, splash and permission screens. |
| P-8 | Rewarded ads are opt-in: state the reward before the user opts in, and never penalise declining or auto-play. |
| P-9 | Native ads carry an "Ad" or "Sponsored" label and the SDK's AdChoices overlay, which must never be covered, resized or moved, and must be visually distinct from the surrounding content. |
| P-10 | Show ads only in the app's foreground UI: never in notifications, media browsers (Android Auto, Wear OS, Assistant), lock screens, cast sessions, widgets or picture-in-picture. |
| P-11 | Ads must not imitate system UI, notifications or app controls. |
| P-12 | Full-screen ads must stay closeable. Never draw over the SDK's close button. |
| P-13 | Paying users see no ads and generate no ad requests: suppress before the request, not after. |

## Consent

- `canRequestAds` is the only gate on requesting an ad.
- Never set a "non-personalised" flag yourself. With a certified consent platform the SDK reads the
  IAB TCF and Global Privacy Platform signals on every request, and a manual flag would serve
  non-personalised ads to users who did consent. The library has no personalised-ads switch for this
  reason.
- Enable IAB TCF in the AdMob console; without it revenue drops with no visible error.
- A privacy-options entry point is required for EEA traffic. Show it only when
  `privacyOptionsRequired` is true.
- After the privacy options form closes, read `canRequestAds` again. Consent can be withdrawn here;
  when it is, destroy live ad views and cancel pending loads.
- A consent failure is never fatal: `canRequestAds` stays false and every placement is suppressed.
  Never block the UI on consent, and never retry it in a loop.

## Audience

`AdAudienceConfig` is required. An app whose store listing declares an audience that includes
children may serve only certified SDKs and no personalised ads, must use
`AgeRestriction.ChildDirected`, and must remove the advertising ID permission. Apps in the App
Store's Kids category cannot use AdMob. Confirm the store declaration before adding ads.

## Rewarded ads and subscriptions

Granting something for watching an ad is not a purchase, so store in-app purchase rules do not apply.
Prefer consumable rewards, such as "no ads for two hours", and keep rewards separate from your
subscription entitlement.

## Kill switches, fastest first

1. Turn off the app's own ads-enabled signal. No release and no network needed.
2. Emit `AdRemoteConfig.AllDisabled`, or a per-placement patch, from your `AdRemoteConfigSource`.
   Emit `AllDisabled` on every failure path.
3. Pause the ad unit in the AdMob console. Slots then collapse on no-fill instead of leaving an empty
   box (P-7).
