<!-- Release: v0.23.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Keep selection groups independent and preserve gallery items throughout reversible pinch gestures; consolidate browse presentation and shared controls.
- Make player dismissal as responsive as the Queue drawer, preserve gesture interruption, and pin the playing song when randomizing its active queue.
- Unify song/collection menus, artwork actions and browse preferences; refresh playback configuration, rounded icons and shadow spacing.
- Add fine reflective glass rims, remove doubled slider tracks, and place discrete stops inside the track’s rounded caps.
- Reduce selection sampling area, batch pinch capture, retain useful backdrop samples and avoid unnecessary screen recomposition during resizing.

Performance measurements use a software-rendered emulator, not the target phone. Physical-device frame pacing and intermittent manual-scroll jitter remain under review ([#167](https://github.com/anastaci1a/localica/issues/167), [#150](https://github.com/anastaci1a/localica/issues/150)).

## Resolved issues

- https://github.com/anastaci1a/localica/issues/157 - Stable queue heading after deletion.
- https://github.com/anastaci1a/localica/issues/158 - Independent, seamless selection groups.
- https://github.com/anastaci1a/localica/issues/159 - Reversible gallery/list pinch previews without disappearing items.
- https://github.com/anastaci1a/localica/issues/160 - Shared glass drag controls, modern playback settings and reliable scrub cancellation.
- https://github.com/anastaci1a/localica/issues/161 - Interruptible player dismissal and queue scroll handoffs.
- https://github.com/anastaci1a/localica/issues/162 - Queue controls and playback-aware randomization.
- https://github.com/anastaci1a/localica/issues/163 - Consistent collection menus, anchors and live sorting preferences.
- https://github.com/anastaci1a/localica/issues/164 - Artwork navigation, transparent status bars and four-tab layout.
- https://github.com/anastaci1a/localica/issues/165 - Unclipped panel and queue shadows.
- https://github.com/anastaci1a/localica/issues/166 - Consolidated presentation, gesture and documentation ownership.
- https://github.com/anastaci1a/localica/issues/168 - Single refracted slider track and contained stop dots.
- https://github.com/anastaci1a/localica/issues/169 - Subtle reflective rims on shared glass surfaces.

## Install

1. Download `localica-v0.23.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
