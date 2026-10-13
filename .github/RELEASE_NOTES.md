<!-- Release: v0.22.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Replace glass optics with a shared curved lens model; preserve contact compression and return rebound, strengthen magnetic controls, and add a draggable/morphing settings preview.
- Reuse scene and shadow textures, fix lost taps during menu closing, remove automatic-scroll discontinuities, and fix the crash when adding navigation tabs.
- Unify playlist destinations and collection menus, add playlist images, prevent duplicate playlist names, and reuse search catalogs for faster results.
- Standardize staged settings pickers and text clearing; seed album colors/metadata from cached content, improve bright-artwork contrast, and add saved lyric timing correction.
- Refresh Localica’s rendered icon and Android music-player discovery. Physical Bluetooth timing and phone frame rates still require device review.

Known follow-ups: intermittent manual-scroll jitter ([#150](https://github.com/anastaci1a/localica/issues/150)) and the newly reported heading clipping after queue deletion ([#157](https://github.com/anastaci1a/localica/issues/157)).

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
- https://github.com/anastaci1a/localica/issues/145 - Ordered dark tones without a scene-wide contrast wash.
- https://github.com/anastaci1a/localica/issues/146 - Immediate cached album colors/metadata and sequential track reveal.
- https://github.com/anastaci1a/localica/issues/147 - Solid paused Play triangle at both player sizes.
- https://github.com/anastaci1a/localica/issues/148 - Profiled shared scene/shadow reuse and bounded panel allocation.
- https://github.com/anastaci1a/localica/issues/149 - Continuous bounded automatic scrolling.
- https://github.com/anastaci1a/localica/issues/151 - Shared lens rendering and continuous modal handoff.
- https://github.com/anastaci1a/localica/issues/152 - Safe addition, removal and reordering of navigation tabs.
- https://github.com/anastaci1a/localica/issues/153 - Continuous popup optics and a single sharp navbar foreground.
- https://github.com/anastaci1a/localica/issues/154 - Scoped search lenses update when their backdrop becomes available.
- https://github.com/anastaci1a/localica/issues/155 - Visible local text contrast, navbar support and a multicolor preview.
- https://github.com/anastaci1a/localica/issues/156 - Library and Search filters refract the finished list fade.

## Install

1. Download `localica-v0.22.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
