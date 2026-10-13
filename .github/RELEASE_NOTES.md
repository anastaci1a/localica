<!-- Release: v0.22.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Rework curved glass optics, contact compression and release rebound; slow visible morphs, strengthen magnetic controls, and add a draggable/morphing settings preview.
- Unify playlist destinations and collection menus, add playlist images, prevent duplicate playlist names, and reuse search catalogs for faster results.
- Standardize staged settings pickers and text clearing; seed album colors/metadata from cached content, improve bright-artwork contrast, and add saved lyric timing correction.
- Refresh Localica’s rendered icon and Android music-player discovery. Physical Bluetooth timing and phone frame rates still require device review.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/120 - Bright-artwork player contrast.
- https://github.com/anastaci1a/localica/issues/123 - Lyric clock interpolation and manual output correction.
- https://github.com/anastaci1a/localica/issues/136 - Curved droplet optics, held contact and release motion.
- https://github.com/anastaci1a/localica/issues/137 - Magnetic selectors and constrained edge pressure.
- https://github.com/anastaci1a/localica/issues/138 - Queue actions and clipped glass dismissal.
- https://github.com/anastaci1a/localica/issues/139 - Inline playlist destinations and unique names.
- https://github.com/anastaci1a/localica/issues/140 - Shared collection actions and playlist artwork.
- https://github.com/anastaci1a/localica/issues/141 - Cached search and shared clear/refocus fields.
- https://github.com/anastaci1a/localica/issues/142 - Fixed Sort/View panels, staged choices and settings alignment.
- https://github.com/anastaci1a/localica/issues/143 - Capitalized app identity, rendered icon and music discovery.
- https://github.com/anastaci1a/localica/issues/144 - Scroll-to-top only on current-tab reselection.
- https://github.com/anastaci1a/localica/issues/145 - Monotonic adaptive glass tint.
- https://github.com/anastaci1a/localica/issues/146 - Immediate cached album colors/metadata and sequential track reveal.
- https://github.com/anastaci1a/localica/issues/147 - Solid paused Play triangle at both player sizes.

## Install

1. Download `localica-v0.22.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
