<!-- Release: v0.30.7 -->

# Release v0.30.7

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Vertical swipes and taps on controls are handled consistently everywhere. Swiping on a switch, slider or button no longer selects it when you meant to scroll, but swipes at the top or bottom of a list, or in menus that can't scroll, select normally. A control that loses the touch to scrolling now visibly releases. Hold-to-lock works for every control.
- From any Queue header control you can swipe down to close the Queue, and from any player control you can swipe to move the player. The playback bar no longer starts a seek on a vertical swipe.
- Sliders and the playback bar jump to your finger immediately. A sideways slide or a short hold locks the selection; moving vertically first cancels it. Every slider has the fly-up (X) to cancel back to the original value, and the menu shifts up to make room when needed.
- When the player minimizes from the lyrics screen, the lyrics (or the no-lyrics icon) stay under the artwork, and the artwork fades in from a position centred for the lyrics layout, in both artwork layouts.
- A seek whose duration changed mid-drag now lands exactly where you released.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/538 - Standardized scroll-vs-tap protection for controls, drawers and the player.
- https://github.com/anastaci1a/localica/issues/539 - Unified slider behaviour: instant touch-down, (X) cancel and menu shift.
- https://github.com/anastaci1a/localica/issues/537 - Lyrics stay under the artwork, with a lyrics-aware artwork start position.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).
- Android 8 album-art crash hardening is in place, but the cause is unconfirmed (https://github.com/anastaci1a/localica/issues/501).
- The subview whole-list freeze (case b of https://github.com/anastaci1a/localica/issues/535) awaits device retest.

## Install

1. Download `localica-v0.30.7-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
