<!-- Release: v0.34.16 -->

# Release v0.34.16

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

This release replaces the shared glass optics in one place, so every glass surface (buttons, tab bar, popups, search, lyrics and the player) changes together. The previous optic code is removed rather than kept behind a setting.

- **Glass refraction:** the backdrop seen through glass now bends with a new edge profile: a stronger inward pull just inside the rim and a soft outward band just outside it. The previous crown and shoulder displacement is removed.
- **Glass colour fill:** glass faces use fixed colour rows applied just before output, and the same face colour is used by both rendering paths. Light glass faces now always get dark ink, so text and icons stay readable over white backdrops and on popups shown over dark pages.
- **Glass shadow:** one shared shadow replaces the previous falloff: a soft shadow shifted 8 dp down with a 24 dp blur, drawn the same way under every glass surface. Shadow rasters no longer depend on live exclusions, and the larger shadow reach does not change layout padding or touch areas.
- **Fluid press:** pressable glass lifts and leans toward the finger while held, then settles back with a spring on release. Pressing again during the settle continues from the current motion instead of restarting.
- **Touch glow:** the light under the finger is replaced, including the rippling glow that follows a press as it moves. The light follows the finger smoothly, a broad glow fades in and out around the press, and its strength adapts to how bright the backdrop is. Switches light up only while they are physically touched.
- App Kotlin/Java source changes from 53,520 to 53,907 lines: 852 added, 465 removed, net +387. Most of the growth is the new touch glow and fluid press; the old refraction and shadow code is removed.
- Refresh shard scheduling from complete v0.34.15 and v0.34.14 producers 44398792857 and 44336375980.

## Open items

- The Depth setting now changes only the highlight. Changing Depth or pressing no longer changes how much the backdrop bends.
- Sliders and switches keep their own press shape and existing lens bounds; joining them to the shared fluid press is follow-up work.
- Glass faces on dark pages use the light colour rows with dark ink; dedicated dark-appearance rows are follow-up work.

## Resolved issues

- [#1005](https://github.com/anastaci1a/localica/issues/1005): Shared glass refraction, colour fill, shadow, fluid press and touch glow are replaced in the shared glass surface. The broader interaction programme remains open.

## Known issues and follow-ups

- [#982](https://github.com/anastaci1a/localica/issues/982): Scoped search history tests can lose the editor node during text replacement.
- [#1047](https://github.com/anastaci1a/localica/issues/1047): A parallel-build scalar allocation check can fail intermittently.
- [#1049](https://github.com/anastaci1a/localica/issues/1049): Pinch slow-frame release coverage with animations enabled remains follow-up work.
- [#1051](https://github.com/anastaci1a/localica/issues/1051): Library data import confirmation has an intermittent Android 8 timeout history; the dialog replacement fix is included, with the known timeout disclosed for release acceptance.
- [#530](https://github.com/anastaci1a/localica/issues/530): The artwork chooser can intermittently time out waiting for “Choose recent image” in ArtworkEditRestorationTest.
- [#1061](https://github.com/anastaci1a/localica/issues/1061): A historical held-drag playback test timed out waiting for idle. That test path has been removed; current cover assertions remain mandatory.
- [#1070](https://github.com/anastaci1a/localica/issues/1070): SublistLoadingPlaceholderCaptureTest#albumLight can fail intermittently on Android 8.
- [#1072](https://github.com/anastaci1a/localica/issues/1072): On Android 8, one touch-light pixel check exceeds its unchanged one-byte limit by one step because the test's rounding differs from the device's byte quantization. The limit is unchanged; this is disclosed as non-blocking.
- [#1073](https://github.com/anastaci1a/localica/issues/1073): Glass refraction rows at the bottom edge can sample unrecorded backdrop padding, so a popup can intermittently show a slightly different colour along its bottom edge.
- [#1074](https://github.com/anastaci1a/localica/issues/1074): Opening and closing glass popups is slower at the long tail on the A35: 90th and 95th percentile frame times rise by about 3 to 4 ms and garbage-collection pauses by about 5 ms compared with v0.34.15. Median frame time and list scrolling are unchanged.
- [#1063](https://github.com/anastaci1a/localica/issues/1063): Investigate count validity when shuffle or repeat changes; this source finding has not been reproduced on a device.
- [#1064](https://github.com/anastaci1a/localica/issues/1064): Add pending-scroll coverage for the list scrollbar path.
- [#1065](https://github.com/anastaci1a/localica/issues/1065): Remove obsolete shuffle test diagnostics while preserving native refresh and assertions.
- [#1066](https://github.com/anastaci1a/localica/issues/1066): Static frosted panels such as the queue drawer still use the previous fixed blur radius.
- [#1067](https://github.com/anastaci1a/localica/issues/1067): Blur radius outside the 2.0–3.0 density range changes without a reference value.
- [#1068](https://github.com/anastaci1a/localica/issues/1068) and [#1069](https://github.com/anastaci1a/localica/issues/1069): Blur test clean-up and removal of one unreachable fallback branch.
- [#1052](https://github.com/anastaci1a/localica/issues/1052), [#1053](https://github.com/anastaci1a/localica/issues/1053) and [#1054](https://github.com/anastaci1a/localica/issues/1054): Filed follow-ups cover shared glass complexity accounting, control ownership, and moving-contact and first-frame press response.
- [#1026](https://github.com/anastaci1a/localica/issues/1026) and [#1029](https://github.com/anastaci1a/localica/issues/1029): Captured Save remains deferred; Save follows the currently browsed queue and eagerly maps large entry lists on Main.
- [#1042](https://github.com/anastaci1a/localica/issues/1042): The Last.fm first-match persistence JVM test intermittently restores a counts-only result. Its root cause remains open.
- [#1039](https://github.com/anastaci1a/localica/issues/1039): Hosted API 36 instrumentation transport can terminate without an assertion stack. Its root cause remains open.
- [#963](https://github.com/anastaci1a/localica/issues/963): Large cold restores remain slow; shuffled million-entry restore still exceeds the standard heap.
- [#964](https://github.com/anastaci1a/localica/issues/964): Broader browse and drag work remains open.
- [#1035](https://github.com/anastaci1a/localica/issues/1035): Million-entry playlist output still runs out of memory.
- [#1033](https://github.com/anastaci1a/localica/issues/1033): Native track-switch latency remains unresolved; no A35 latency improvement is claimed.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Native playback can advance between the Main-thread check and per-edit timeline messages.
- [#1002](https://github.com/anastaci1a/localica/issues/1002), [#642](https://github.com/anastaci1a/localica/issues/642) and [#986](https://github.com/anastaci1a/localica/issues/986): Later glass adoption and qualified profile comparison remain open.
- The 8.3 ms whole-frame A35 objective remains unmet and unchanged. This release makes no qualified frame-timing claim.

## Install

1. Download `localica-v0.34.16-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Schema 9 cannot be reopened by older schema-6, schema-7 or schema-8 APKs. Keep a backup before downgrading.
