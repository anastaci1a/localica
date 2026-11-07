<!-- Release: v0.33.7 -->

# Release v0.33.7

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Extend Glass brightness weighting to 1–8 without inverting brightness; saved settings keep their look.
- Let focused prototype test runs account for API-gated tests and optionally run on the owner's self-hosted runner.
- Refresh the measured test shard costs and tidy the prototype test inventory.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/633 - Raise the Glass brightness weighting maximum to 8 while keeping brightness monotone.
- https://github.com/anastaci1a/localica/issues/464 - Derive the prototype expected-method inventory from API gating.
- https://github.com/anastaci1a/localica/issues/592 - Add an opt-in, owner-only self-hosted runner for single focused prototype runs.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.7-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
