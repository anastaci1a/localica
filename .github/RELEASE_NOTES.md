<!-- Release: v0.24.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Hold playlist/queue songs to select, then move the same finger to drag. Lifted gallery items retain their artwork layout and land without a final position jump.
- Unify quiet-menu disappearance and returning glass shadow ownership; make small-control rebound more visible.
- Tighten Queue controls and match the playing-row texture to its highlight. Move album actions to the bottom-right; keep album tracks in disc/track order.
- Remove queue Move up/down actions and the main menu’s Close Localica action.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/170 - Mutable holds and faithful drag landing.
- https://github.com/anastaci1a/localica/issues/171 - Popup exits, return shadows and rebound.
- https://github.com/anastaci1a/localica/issues/172 - Queue layout and album collection actions.

Contrast, slider rendering and view-option changes are tracked for the following releases. Physical-device frame pacing remains under review in https://github.com/anastaci1a/localica/issues/167.

## Install

1. Download `localica-v0.24.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
