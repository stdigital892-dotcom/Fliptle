# Accessibility module notes

The Accessibility feature lives entirely in `app/src/main/java/com/fliptle/app/accessibility/`
and is isolated: nothing else depends on it, so turning the service off in
Android's Accessibility settings disables the whole feature without affecting the
rest of the app.

## What it does
- **Browser URL blocking + safe-search** (`UrlBlockAccessibilityService`,
  `SafeSearch`): reads the address bar of browsers and blocks blocklisted domains
  / enforces safe-search. Only while a freeze is active. Nothing is stored or sent.
- **In-app surface blocking** (`SurfaceDetector`, `SurfaceBlocklist`): detects and
  blocks Instagram Reels/Stories and YouTube Shorts, each individually toggleable
  in Setup → "In-app surfaces". The rest of those apps stays usable.

## View IDs / signatures being matched (HONEST CAVEAT)
These are matched against the **current** builds of Instagram and YouTube and are
**not a public API**. Both apps use obfuscated / internal resource IDs that can
change with any update, which would silently break detection until the substrings
below are updated.

A block fires only when BOTH conditions hold — this is what keeps it from
over-matching the feed / nav tabs / home shelf:

1. A node's view id matches the substring below, **and**
2. that node is **near full-screen** (≥ 90% of window width and ≥ 75% of height),
   i.e. the immersive player — not an inline feed preview or a tab button.

Content descriptions are **not** used to classify (the string "Shorts" is on
YouTube's nav tab and home shelf, which previously blocked the whole app on
launch).

| Surface            | App package                     | View-id match (must also be full-screen) |
|--------------------|---------------------------------|------------------------------------------|
| Instagram Reels    | `com.instagram.android`         | contains `clips_viewer` |
| Instagram Stories  | `com.instagram.android`         | contains `reel_viewer` |
| YouTube Shorts     | `com.google.android.youtube`    | contains `reel_recycler` / `reel_player` / `reel_watch` |

(Browser address bars are matched by per-browser view ids in
`UrlBlockAccessibilityService.urlBarIdsFor`, with a generic node-text fallback —
same fragility caveat applies.)

### Debug mode
Setup → In-app surfaces → **"Debug: log matches, don't block"**. While on, nothing
is blocked and the service records the view ids / content descriptions / bounds it
sees for Instagram/YouTube into an on-device log (also Logcat tag
`FliptleSurface`). Use it to capture the **real** ids on your device if an update
breaks detection, then update the substrings in `SurfaceDetector.kt`.

## Enforcement
On a match, the service performs a Back action (escalating to Home if the surface
is still detected shortly after) and shows a brief "Blocked" overlay. Back returns
the user to the feed / normal app rather than closing it, so only the chosen
surface is blocked.
