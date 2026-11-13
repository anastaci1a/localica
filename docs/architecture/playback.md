# Playback

One `MediaSessionService` owns the player. Everything else, including the app's own screens, sends commands. An explicit play or pause is recorded in a revisioned mailbox before anything can delay it, so the newest intent always wins. Position is saved as checkpoints. A play is counted only after real listening.

- The UI and the system (notification, headset, media browsers) use the same controller.
- Passive restoration never starts playback. Only an explicit request does.
- No disk wait blocks the main thread, at start or at shutdown.
- Listening statistics live in their own database and survive queue and folder deletion.
- Status: Implemented.

## Ownership

Only the service owns ExoPlayer. It exposes Media3 and legacy MediaBrowser discovery. Play, pause, next, previous, shuffle and repeat go through `QueueController`, from the UI and the system alike. Position and incremental seeks forward to the service-owned player.

The screen observes metadata and transport changes. Position samples are read inside the player surface and when a seek executes, not pushed through the whole UI.

## Intent

| Rule | Why |
| --- | --- |
| The mailbox records an accepted play or pause before the command actor can suspend. | A slow storage write cannot reorder what the user asked for. |
| Intent is rechecked atomically at native preparation and at play. Only applied requests are consumed. | A request is never lost or applied twice. |
| A newer explicit Pause or Clear supersedes a pending Play. | This holds through readiness, suspended activation and edit holds. |
| Native focus suppression is kept, and is not a manual Pause. | A phone call must not change what the user asked for. |
| Passive restoration is distinct from explicit intent. | Launch alone never autoplays. |

Setting edits apply against the actor's current preferences. A staged dialog commits only the fields it edited.

## Starting

A queue starts playing before its full contents are persisted. A loaded selection seeks without rebuilding or querying. After native activation, the service handles the contiguous eligible transport prefix while the ordered save waits. It stops at the first structural command or edit hold. Creation and structural receipts still require their commits.

Decoded PCM prefill targets 50 ms or the native buffer capacity, whichever is smaller. Play waits for accepted PCM, native backpressure or a 30 ms playback-loop deadline. That deadline is not a wall-clock guarantee. Pause, flush and release cancel a delayed start. Encoded, offloaded and tunneled output keeps its existing start policy.

Native arrivals are captured immediately, even during storage work. The callback batch finishes before the timeline is extended or persisted.

## Continuity

- Preload roughly five seconds of the actual next song under the active repeat and shuffle modes. On by default.
- Preserve intentional silence and encoder timing. Audio never overlaps.
- Previous: below five seconds, go to the previous song; otherwise restart. The threshold is 1 to 60 seconds, or always previous, or always restart.
- Without a previous song in the active traversal, Previous restarts.
- Paused or playing state is preserved across app, system and headset controls.

Format limits are in [Build](../development/build.md#formats-and-dependencies). Boundary corrections are in [Audio corrections](audio-corrections.md).

## Checkpoints and shutdown

Cursors are captured on events, every five seconds and at service destruction. Writes are serialized and retried from durable state.

| Ending | What is restorable |
| --- | --- |
| Controlled shutdown, final write succeeds | The final position. Player cleanup waits for that write, up to two seconds. |
| Timeout or write failure | The last committed checkpoint. |
| Abrupt termination | The last committed checkpoint. |

Five seconds is the capture cadence. It is not a disk deadline when the actor, scheduler or storage stalls. Task removal defers stopping an inactive service through the same barrier. Ongoing playback keeps Media3's policy.

## Qualified plays

| Term | Rule |
| --- | --- |
| Qualified play | Actual listening for 30 seconds or half the duration, whichever is shorter. Once per traversal. Paused time and seek jumps are excluded. |
| Traversal | A pause resumes it. An automatic repeat, an occurrence switch or an explicit restart at zero begins another. |
| Tag edits | Keep the same visit and exclude editing time. |
| Process death | Unqualified partial listening may be lost. |

Playing time is measured with a monotonic clock in the service. Writes retry stable event IDs, so nothing is counted twice. Qualified plays are local, all-time and durable. Historical dates or counts are never invented.

## Rankings

- Collections sum the counts of their current distinct songs.
- Playlist counts follow each queue occurrence's originating playlist, even after queue switches. Legacy unknown origins stay unknown.
- Most played orders by per-song count. Longest listened weights each count by the song's duration; unknown duration counts as zero, and overflow saturates.
- Ranking windows use the caller's time zone and inclusive event boundaries.

A provider interface keeps local and external counts separate. Settings select external counts with per-song local fallback. The external provider is described in [Online](online.md#lastfm).

## Player projections

Player projections reuse occurrence and metadata lookups across position updates. They keep first-match identity, and distinct action and lyrics fallbacks for unavailable tracks.

## Tests

| Topic | Classes |
| --- | --- |
| Intent and start | `PlaybackIntentTest`, `PlaybackStartTest`, `InitialSaveTransportTest`, `LargeQueueTransportTest` |
| Shutdown | `PlaybackShutdownTest` |
| Continuity | `PreviousPlaybackTest`, `ContinuousPlaybackTest` |
| Statistics | `PlaybackStatisticsTest`, `PlaybackStatisticsLifetimeTest`, `StatisticsStorageTest`, `LongestListenedSortTest` |
| Projections | `PlayerTrackProjectionTest`, `PlaybackObservationTest` |
