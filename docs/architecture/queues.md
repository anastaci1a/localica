# Queues and playlists

A queue or playlist is an ordered list of occurrences. An occurrence is one appearance of a song, with its own identity, so the same song can appear twice. A queue is a snapshot of a list at the moment of the tap.

It does not change when its source list changes. Modes change how a queue is traversed, never its saved order.

- Tapping a song snapshots the complete displayed list, in its filtered and sorted order, and starts at the tapped occurrence.
- An identical queue is reused, not duplicated.
- Shuffle is a traversal over occurrences. It never reorders the visible list.
- Sorting a queue or playlist physically reorders it and keeps a Manual baseline to return to.
- Status: Implemented.


## The model

An occurrence stores its own ID and a shared track snapshot. Physical order, current cursor, visit history and Manual baseline are separate facts. Available songs project current metadata; unavailable songs retain saved references. Controllers serialize transactions. Cursor-only writes do not rewrite entries, and absent snapshot fields stay unknown.

## Creating a queue

Song taps snapshot the complete displayed order. Preferences may instead insert-and-play, add next or append, with scoped inheritance; explicit selection commands bypass tap preferences. Names describe their source, such as `Album ◀ Artist`. Mixed search results contribute displayed songs only.

### Reuse

Reuse compares intended name, ordered song identities and playlist origins. Prefer the active exact match, then the first saved match. Start the selected occurrence at zero while preserving occurrence IDs and history. Metadata, counts and position do not distinguish queues; differing order, duplicates, names or origins do. Never infer names from suffixes or merge queues.

## Managing queues

Restore paused unless an explicit Play is pending. Browsing defaults to leaving playback unchanged; choosing an occurrence activates its queue. Active deletion restores the most recently activated remaining queue, independent of manual order. Retention is unlimited by default; optional removal requires confirmation and protects the active queue.

## Traversal

Global Repeat/Shuffle default off. Shuffle changes traversal, never physical order. One concrete future is shared by transport, preload and artwork; projections never count as visits. History retraces actual visits across repeat cycles/restoration. Explicit selection replaces forward history; exhaustion starts another cycle.

Mode changes preserve current occurrence/position. Enabling Shuffle starts a fresh traversal; disabling it resumes visible order. Reorders preserve plans; additions join and removals invalidate visits. Journey tests specify these transitions.

### Play Next

A batch inserts after current in physical order and traversal, before shuffled forward history. Newer batches precede earlier batches while retaining internal order. Scheduled visits become visited on arrival and survive explicit selection/restoration.

### Repeat

Song-end choices are Stop, Load next paused, Next and Repeat. Queue-end choices are Stop, Next queue and Repeat, with reset/reshuffle options. Retraced or scheduled visits retain song-end rules; queue-end rules apply only beyond the traversal.

## Sorting and the Manual baseline

Automatic sorts retain Manual order. Actual manual moves, Randomize and Reverse replace it; no-op moves do not. Revert restores the order/settings captured when options opened, reconciling later additions/removals. Failures remain retryable. IDs, cursor and history survive every reorder; active randomization places current first.

## Playlists

Names are trimmed, case-insensitive and unique for create/rename/import without rewriting legacy duplicates. Destination additions commit atomically. Local M3U/M3U8/PLS imports resolve against indexed music and require review of ambiguous/missing entries; no remote audio is fetched. Imports cap at 5 MiB/20,000 entries. Custom covers require square crops; cleanup removes only unreferenced owned files.

## Storage

Traversal namespaces and real anchor tokens stabilize projected neighbours without inventing visits. Media3 receives a bounded window of tokened visits in traversal order. Legacy per-queue modes import once, then remain inert. Storage and compatibility tests own the migration cases.

## Tests

[QueueDatabaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueDatabaseTest.kt).

[PlaylistDatabaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaylistDatabaseTest.kt).

[OrderedJourneyDatabaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/OrderedJourneyDatabaseTest.kt).

[QueuePersistenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueuePersistenceTest.kt).

[QueueRestoreTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueRestoreTest.kt).

[QueueBrowsingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueBrowsingTest.kt).

[QueueReusePlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueReusePlaybackTest.kt).

[QueueSaveOriginTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueSaveOriginTest.kt).

[ShuffleModePlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ShuffleModePlaybackTest.kt).

[PlayNextJourneyTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayNextJourneyTest.kt).

[QueueJourneyPlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueJourneyPlaybackTest.kt).

[QueueJourneyPersistenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueJourneyPersistenceTest.kt).

[AdvancedRepeatTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/AdvancedRepeatTest.kt).

[BrowseSortRevertTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowseSortRevertTest.kt).

[RevertOrderPersistenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/RevertOrderPersistenceTest.kt).

[ReverseOrderPersistenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ReverseOrderPersistenceTest.kt).

[LiveOrderPersistenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LiveOrderPersistenceTest.kt).

[QueueRandomizePlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueRandomizePlaybackTest.kt).

[PlaylistCompletionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaylistCompletionTest.kt).

[PlaylistCoverActionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaylistCoverActionTest.kt).

[ShuffleApkCompatibilityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ShuffleApkCompatibilityTest.kt).
