<!-- Release: v0.34.2 -->

# Release v0.34.2

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Adopt the measured Apple popup motion model with shared spring flight, retained origins and bounded geometry.
- Prevent crashes when More is reopened during its return animation.
- Close popup width and height together while preserving the opening motion.
- Add an Animation speed slider and a Respect system animation scale toggle.
- Enforce shared popup ownership and prevent new screen-owned popup overrides through release gates.
- Improve native keyboard isolation, Reduced motion fixtures and prototype verification policy.
- Reduce inherited UI debt from 1,487 to 1,480: RawMotionMap 46 → 40 and NonPresetScreenLayout 283 → 282.

## Resolved issues

- [#854](https://github.com/anastaci1a/localica/issues/854): Adopt the accepted Apple popup motion model across shared popup presentations.
- [#855](https://github.com/anastaci1a/localica/issues/855): Deliver the measured popup model adopted through #854.
- [#860](https://github.com/anastaci1a/localica/issues/860): Replace Faster animations with the Animation speed slider.
- [#862](https://github.com/anastaci1a/localica/issues/862): Add the Respect system animation scale toggle.
- [#868](https://github.com/anastaci1a/localica/issues/868): Share complete-lane deferrals and diagnostic skip policy with prototype verification.
- [#871](https://github.com/anastaci1a/localica/issues/871): Refresh shard scheduling durations from the complete v0.34.1 producer.
- [#872](https://github.com/anastaci1a/localica/issues/872): Cover four retained Settings popup origins at Standard speed with law-bounded waits.
- [#874](https://github.com/anastaci1a/localica/issues/874): Restore native keyboard policy and retire keyboard state between test owners.
- [#878](https://github.com/anastaci1a/localica/issues/878): Prevent negative corner radii and bound spring projections used by player geometry and text.
- [#879](https://github.com/anastaci1a/localica/issues/879): Bind Reduced motion test fixtures to matching Engine records.
- [#884](https://github.com/anastaci1a/localica/issues/884): Enforce shared popup ownership, API boundaries and duplicate-role checks.

- [#903](https://github.com/anastaci1a/localica/issues/903): Correct selection stacking fixtures and inspect only discriminating opaque pixels.
- [#921](https://github.com/anastaci1a/localica/issues/921): Inspect popup-depth hardware bitmaps through exact software copies.
- [#924](https://github.com/anastaci1a/localica/issues/924): Observe merged browser readiness before scrolling the tag editor flow.
- [#925](https://github.com/anastaci1a/localica/issues/925): Retain safe popup sampling until the home stops projecting the closing flight.
- [#926](https://github.com/anastaci1a/localica/issues/926): Remove the separate cross-axis close law so popup width and height shrink together.

## Known issues and follow-ups

- [#887](https://github.com/anastaci1a/localica/issues/887): The queue selector title fix is excluded from this release.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a ratchet; new overrides are forbidden and inherited counts may only decrease.
- [#904](https://github.com/anastaci1a/localica/issues/904): API 26 shadow fixtures can capture Launcher before foreground admission.
- [#915](https://github.com/anastaci1a/localica/issues/915): Single-Tone reference captures are not deterministic; removal of that rendering mode is planned in [#919](https://github.com/anastaci1a/localica/issues/919).
- [#917](https://github.com/anastaci1a/localica/issues/917): A pre-existing timing-dependent page offset can appear after the Queue drawer test.
- [#920](https://github.com/anastaci1a/localica/issues/920): Reversing the bottom popup during opening can exceed its cross-axis band; reversal work remains deferred.
- The 8.3 ms whole-frame performance objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.2-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
