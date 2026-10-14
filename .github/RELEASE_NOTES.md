<!-- Release: v0.25.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Rebuild adaptive contrast around page-wide icon colors, with adjustable brightness trim and weighting.
- Add directional inset glass highlights and adjustable Classic selector opacity.
- Render sliders and switches from one scene; respond to contact immediately and keep vertical rebounds bounded.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/173 - Page-coherent contrast and Classic selector opacity.
- https://github.com/anastaci1a/localica/issues/174 - Directional inset highlights.
- https://github.com/anastaci1a/localica/issues/175 - Slider composition, contact and rebound direction.
- https://github.com/anastaci1a/localica/issues/178 - Enough CI time for the unchanged full emulator suites.

View options and the final performance pass follow this release. Physical-device frame pacing remains under review in https://github.com/anastaci1a/localica/issues/167.

## Install

1. Download `localica-v0.25.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
