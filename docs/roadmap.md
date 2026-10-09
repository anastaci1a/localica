# Roadmap

## Current scope: v0.1.2

One selected folder, scanned recursively into a persistent library. A bottom bar has centered Player (left) and Library (right) tabs, with Settings at the far right. Selecting a library item plays that track. The player shows track details, play/pause, elapsed and total time, and seeking. Playback stops at the end; queues, repeat, shuffle, and automatic advancement are deferred.

The implemented foundation separates document access, scanning, persistence, playback, and presentation inside one Android module. Verification covers scan rules, failed/cancelled scans, persistence, navigation, playback, and end-of-track behavior. Acceptance is tracked in [issue #1](https://github.com/anastaci1a/localica/issues/1).

CI builds APK artifacts and publishes development prereleases from `vX.X.X` tags after checks pass. Public distribution signing remains a separate decision.

## Later direction

The next decision is which improvements matter after trying the MVP on a physical device. Expand library navigation and listening controls from that feedback. The broader [product targets](product.md) remain candidates rather than commitments for this release.
