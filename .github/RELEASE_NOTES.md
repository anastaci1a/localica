<!-- Release: v0.27.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Give settings controls white resting thumbs, clear active lenses and adjustable expansion.
- Add navbar overlay darkening and optional refraction of icons/text; make selector rebound symmetric.
- Correct backdrop ownership, shared shadow bounds, popup returns and scoped-search sizing.
- Align collection art with the status bar, match player/Queue corners to the device and use inverse-color player handles.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/179 - Shared settings controls and gesture ownership.
- https://github.com/anastaci1a/localica/issues/180 - Backdrop, shadow and popup continuity.
- https://github.com/anastaci1a/localica/issues/181 - Navigation lens layering and rebound.
- https://github.com/anastaci1a/localica/issues/182 - System geometry and player handle contrast.

Performance measurements and physical-device follow-up remain in https://github.com/anastaci1a/localica/issues/167.

## Install

1. Download `localica-v0.27.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
