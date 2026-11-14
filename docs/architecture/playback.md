# Playback

One `MediaSessionService` owns the player. Everything else, including the app's own screens, sends commands. An explicit play or pause is recorded in a revisioned mailbox before anything can delay it, so the newest intent always wins. Position is saved as checkpoints. A play is counted only after real listening.

- The UI and the system (notification, headset, media browsers) use the same controller.
- Passive restoration never starts playback. Only an explicit request does.
- No disk wait blocks the main thread, at start or at shutdown.
- Listening statistics live in their own database and survive queue and folder deletion.
- Status: Implemented.


## Ownership

Only the MediaSessionService owns ExoPlayer. UI, notifications, headsets and media browsers share QueueController commands. Position sampling stays inside the player surface/seek path rather than invalidating the whole UI.

## Intent

Accepted Play/Pause enters a revisioned mailbox before actor suspension. Native preparation/play rechecks it atomically and consumes applied requests only. Newer Pause/Clear supersedes pending Play through startup and edit holds. Focus suppression remains distinct from manual Pause. Passive restoration cannot autoplay. Intent tests specify interruption sequences.

## Starting

Native playback may start before a full queue save. Only the native window retains playback templates. Each synchronization refreshes metadata and grants off Main and reuses unchanged templates. Eligible transport commands proceed while structural commits wait. Callback batches finish before timeline extension/persistence.

Decoded PCM prefill targets 50 ms or buffer capacity, whichever is smaller. Start waits for accepted PCM, backpressure or a 30 ms playback-loop deadline; cancellation/flush/release cancels delayed start. Encoded/offloaded/tunneled policy remains unchanged. Start and transport tests own ordering cases.

## Continuity

Preloading defaults on and prepares roughly five seconds of the actual next traversal song. Preserve intentional silence and encoder timing without overlap. Previous defaults to restart after five seconds, otherwise previous; configurable threshold is 1–60 seconds or always previous/restart. Missing predecessors restart, preserving requested playing/paused state.

[Build](../development/build.md#formats-and-dependencies) owns formats; [Audio corrections](audio-corrections.md) owns boundary adjustments.

## Checkpoints and shutdown

Events, five-second cadence and destruction capture cursors. Serialized durable writes retry. Controlled shutdown waits up to two seconds for the final write; timeout/failure/abrupt termination restores the last committed checkpoint. Capture cadence is not a disk deadline. Inactive task removal shares that barrier; ongoing playback keeps Media3 policy.

## Qualified plays

Real listening qualifies once per traversal at 30 seconds or half-duration, whichever is shorter. Pauses/seek jumps/editing time do not count. Pauses resume visits; repeats, occurrence switches and explicit restarts begin new ones. A monotonic clock and stable event IDs prevent duplicate counting. Unqualified partial listening can be lost on process death; committed local history survives queue/folder deletion.

## Rankings

Collections sum distinct current songs. Playlist counts follow originating occurrences; unknown legacy origins stay unknown. Most played uses counts; Longest listened weights counts by duration with zero for unknown duration and saturating overflow. Windows use caller time zone and inclusive event boundaries. [Online](online.md#lastfm) supplies optional external counts with local fallback.

## Player projections

Projections reuse occurrence/metadata lookups across position updates while retaining first-match identity and separate action/lyric fallbacks for unavailable tracks.

## Tests

[PlaybackIntentTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackIntentTest.kt).

[PlaybackStartTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackStartTest.kt).

[InitialSaveTransportTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/InitialSaveTransportTest.kt).

[LargeQueueTransportTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LargeQueueTransportTest.kt).

[PlaybackShutdownTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackShutdownTest.kt).

[PreviousPlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PreviousPlaybackTest.kt).

[ContinuousPlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ContinuousPlaybackTest.kt).

[PlaybackStatisticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackStatisticsTest.kt).

[PlaybackStatisticsLifetimeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackStatisticsLifetimeTest.kt).

[StatisticsStorageTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/StatisticsStorageTest.kt).

[LongestListenedSortTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LongestListenedSortTest.kt).

[PlayerTrackProjectionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerTrackProjectionTest.kt).

[PlaybackObservationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaybackObservationTest.kt).
