<!-- Release: v0.28.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Correct settings-control rendering, shadow ownership and popup-return continuity; reduce unnecessary artwork and glass work.
- Stabilize scoped-search placement, keyboard dismissal and result transitions; keep button drags from scrolling their parent.
- Share catalog preferences with Library filters; restore album/queue view choices and position-aware pinch anchors.
- Apply queue/playlist sorts as saved reorder operations with a restorable Manual order.
- Rework player actions into a reflowing drag-and-drop pool with Compact spacing and long-press customization.
- Modernize selection actions, preserve playlist-name drafts on Back, and refresh bulk metadata after the batch.
- Allow empty lyrics pages and omit duplicate plain versions of synced sources.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/179 - Shared settings controls and gesture ownership.
- https://github.com/anastaci1a/localica/issues/180 - Backdrop, shadow and popup continuity.
- https://github.com/anastaci1a/localica/issues/181 - Navigation lens layering and rebound.
- https://github.com/anastaci1a/localica/issues/182 - System geometry and player handle contrast.
- https://github.com/anastaci1a/localica/issues/183 - Browse preferences, navigation and view geometry.
- https://github.com/anastaci1a/localica/issues/184 - Mutable-list sorting and saved order.
- https://github.com/anastaci1a/localica/issues/185 - Selection, playlist and metadata workflows.
- https://github.com/anastaci1a/localica/issues/186 - Empty lyrics access and distinct sources.
- https://github.com/anastaci1a/localica/issues/188 - Player-action configuration and long presses.
- https://github.com/anastaci1a/localica/issues/189 - Scoped-search geometry, dismissal and results.
- https://github.com/anastaci1a/localica/issues/190 - Scrub cancellation preview and consistent haptics.
- https://github.com/anastaci1a/localica/issues/191 - Button-owned drag gestures.
- https://github.com/anastaci1a/localica/issues/192 - Averaged material-only backing on Android 13+.

Automatic lyrics lookup remains planned in https://github.com/anastaci1a/localica/issues/187. Physical-device performance follow-up remains in https://github.com/anastaci1a/localica/issues/167.

## Install

1. Download `localica-v0.28.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
