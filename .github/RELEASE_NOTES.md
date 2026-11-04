<!-- Release: v0.32.0 -->

# Release v0.32.0

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- The multi-select pill uses the standard Liquid Glass material everywhere, and its glass and icons follow each menu's light or dark theme. In the Queue drawer it floats over the songs without cutting a new edge into the list.
- The shadow under selected items becomes a soft white glow on light-themed menus. Its spread matches the Glass shadow spread. A new Glass setting, Lift shadow strength (50-150% of the Glass shadow strength, default 100%), controls it, and the default is much lighter than before.
- Selection outlines have 1.75× more padding around items, the same in every view.
- Every gallery size uses one standard gap, between covers and from an item's text to the cover below.
- Popups expand from and retract into an item's current lifted position and size, in lists, galleries, carousels and the Queue.
- The ⋯ button in the multi-select pill retracts its popup into a glass button shape, then the glass fades away, the same as list-row ⋯ buttons.
- The (X) glass that appears while you drag the playback bar refracts the whole player behind it, including the controls and text.
- While a multi-select is active, Back ends the selection instead of offering to close Localica. Once nothing is selected, Back works normally again.
- When you pinch to another view size, items that are leaving fade out one after another across the whole pinch, farthest from your fingers first, and reversing the pinch plays it backwards.
- With a very large queue, Next, Previous and seek respond right after playback starts, instead of waiting for the queue to finish saving.
- Play Next songs keep their next-up place when you pick another song or a track change was already loading. Going back across a shuffle cycle no longer repeats a song, and Stop here and the repeat modes stay on the song that just ended.
- More → Settings → About and licenses → Export diagnostics shares a small text file of recent pinch and list diagnostics. Use it to capture the rare frozen-list state for #535. Nothing leaves the device unless you share it.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/552 - Multi-select pill: standard Liquid Glass material, menu-matched theming, no Queue clipping edge.
- https://github.com/anastaci1a/localica/issues/553 - Theme-aware selection lift shadow and glow, with a configurable strength.
- https://github.com/anastaci1a/localica/issues/558 - Selection outline padding increased 1.75×.
- https://github.com/anastaci1a/localica/issues/561 - One standard gallery gap at every gallery size.
- https://github.com/anastaci1a/localica/issues/554 - Popup home shapes track the lifted item geometry.
- https://github.com/anastaci1a/localica/issues/555 - Plain buttons retract their popup into glass, then fade the glass out.
- https://github.com/anastaci1a/localica/issues/562 - Seek-cancel (X) glass refracts the whole player.
- https://github.com/anastaci1a/localica/issues/563 - Back during a multi-select ends the selection.
- https://github.com/anastaci1a/localica/issues/559 - Disappearing items fade in a staggered sequence across the pinch.
- https://github.com/anastaci1a/localica/issues/214 - Large-queue transport stays responsive while the initial queue save runs.
- https://github.com/anastaci1a/localica/issues/549 - Play Next edge cases: in-flight changes, forward skips, cycle boundaries, no current entry.

## Known issues

- The whole-list freeze after a fling followed by a pinch in subviews is still being investigated. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).
- Selection outlines don't yet merge with smooth concave joins (https://github.com/anastaci1a/localica/issues/556), and the live frosted drag stack is coming in a later release (https://github.com/anastaci1a/localica/issues/557).
- The theme switch into and out of lyrics still jumps in a single frame (https://github.com/anastaci1a/localica/issues/564).

## Install

1. Download `localica-v0.32.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
