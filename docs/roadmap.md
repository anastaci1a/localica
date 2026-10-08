# Roadmap

## First usable release: v0.1.0

One selected folder, scanned recursively into a persistent library. A bottom bar has centered Player (left) and Library (right) tabs, with Settings at the far right. Selecting a library item plays that track. The player shows track details, play/pause, elapsed and total time, and seeking. Playback stops at the end; queues, repeat, shuffle, and automatic advancement are deferred.

The foundation separates document access, scanning, persistence, playback, and presentation inside one Android module. Verification covers scan rules, failed/cancelled scans, persistence, navigation, playback, and end-of-track behavior. Work is tracked in [issue #1](https://github.com/anastaci1a/localica/issues/1).

After a working build, add CI APK artifacts and `vX.X.X` release automation. Public distribution signing remains a separate decision; the first APK is for development testing.

## Later direction

Expand library navigation and listening controls from actual use. The broader [product targets](product.md) remain candidates rather than commitments for this release.
