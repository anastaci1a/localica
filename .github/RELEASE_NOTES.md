<!-- Release: v0.21.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Apply the Apple-sourced glass specification: deeper rounded lenses, adjustable low damping and depth, pressure-driven deformation, pinned contact edges and faster transitions.
- Keep glass attached to scrolling content; improve player gestures, artwork handoff, bounded auto-scroll and pinch continuity.
- Polish collection flyouts, queue navigation, lyrics availability, title fades, Info artwork access and Settings Back motion. Update the illustrated glass guide and interactive lab.
- Reuse collection thumbnails immediately, fade in larger artwork and retain tab/Home artwork through two Back destinations. Center lyrics transport between the seek track and queue shelf; restore minimize arrows and a smoothly expanding lyrics/source pill.
- Combine Sort/View into one tabbed flyout, favor vertical list scrolling, refine clear settings sliders/switches and add release-on-X scrub cancellation.
- Expand the Apple-sourced controls guide and refine cancellation, disabled feedback and right-to-left tracking. Add default blur-through button/autoplay artwork changes, an optional carousel, and an independent artwork swipe toggle. Gate every lyrics entry point on local sources.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/110 - Tunable glass physics, optics and queue controls.
- https://github.com/anastaci1a/localica/issues/111 - Popup flashes, disappearing triggers and closing input delays.
- https://github.com/anastaci1a/localica/issues/112 - Selection clipping and shared overscroll.
- https://github.com/anastaci1a/localica/issues/113 - Collection parallax and album-carousel gesture ownership.
- https://github.com/anastaci1a/localica/issues/114 - Continuous feathering and background colors during player expansion.
- https://github.com/anastaci1a/localica/issues/115 - Pinch recognition and fast gesture continuity.
- https://github.com/anastaci1a/localica/issues/116 - Bounded long-list return scrolling.
- https://github.com/anastaci1a/localica/issues/117 - Interruptible artwork swipes and easier player expansion/collapse.
- https://github.com/anastaci1a/localica/issues/118 - Collection submenu anchors, compact sizing and tint continuity.
- https://github.com/anastaci1a/localica/issues/119 - Lyrics availability, Settings Back, record icons, title fades and Info artwork access.
- https://github.com/anastaci1a/localica/issues/121 - Browse artwork retention, detail resolution fades and Home caching.
- https://github.com/anastaci1a/localica/issues/122 - Lyrics transport alignment.
- https://github.com/anastaci1a/localica/issues/124 - Player and Queue minimize controls, morphing lyrics pill and shared dividers.
- https://github.com/anastaci1a/localica/issues/125 - Deeper mini-player/menu triggers, flatter tab navigation and tighter compact typography.
- https://github.com/anastaci1a/localica/issues/126 - Subtle shadow-weighted glass tint that preserves bright artwork.
- https://github.com/anastaci1a/localica/issues/127 - Whole-pill Sort/View panel with a shared draft.
- https://github.com/anastaci1a/localica/issues/128 - Vertical-biased main-tab gesture recognition.
- https://github.com/anastaci1a/localica/issues/129 - Apple-sourced slider/switch specification and controls.
- https://github.com/anastaci1a/localica/issues/130 - Playback scrub cancellation target.
- https://github.com/anastaci1a/localica/issues/131 - Interactive control specification and implementation review.
- https://github.com/anastaci1a/localica/issues/132 - Blur-through transitions and artwork swipe toggle.
- https://github.com/anastaci1a/localica/issues/133 - Current-song lyrics gating across entry points.
- https://github.com/anastaci1a/localica/issues/134 - Deterministic pause/seek and natural-end verification.
- https://github.com/anastaci1a/localica/issues/135 - Search focus across keyboard/window handoffs.

## Install

1. Download `localica-v0.21.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
