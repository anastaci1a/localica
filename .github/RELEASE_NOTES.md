<!-- Release: v0.20.2 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Cache selection optics while scrolling, bound and downsample blurred backings, and show selection immediately without diagonal gallery connectors.
- Keep overflowing navigation inside its capsule; drag the selector from the bubble or scroll extra tabs from the surrounding strip.
- Share pressure-driven glass deformation, retain surfaces across menu handoffs, and render continuous, shape-aware shadows around bottom controls.
- Use wrapping glass text fields in settings and dialogs; Back ends editing without discarding the draft.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/104 - Selection rendering cost and scrolling alignment.
- https://github.com/anastaci1a/localica/issues/105 - Overflowing navigation, elastic controls, menu handoffs and text focus.
- https://github.com/anastaci1a/localica/issues/106 - Continuous glass shadows without stacked bottom-chrome shading.

## Install

1. Download `localica-v0.20.2-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
