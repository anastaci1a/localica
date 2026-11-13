# Queues and playlists

A queue or playlist is an ordered list of occurrences. An occurrence is one appearance of a song, with its own identity, so the same song can appear twice. A queue is a snapshot of a list at the moment of the tap. It does not change when its source list changes. Modes change how a queue is traversed, never its saved order.

- Tapping a song snapshots the complete displayed list, in its filtered and sorted order, and starts at the tapped occurrence.
- An identical queue is reused, not duplicated.
- Shuffle is a traversal over occurrences. It never reorders the visible list.
- Sorting a queue or playlist physically reorders it and keeps a Manual baseline to return to.
- Status: Implemented.

## The model

| Thing | Stored as |
| --- | --- |
| Occurrence | An independent ID plus a shared track snapshot. |
| Order | The physical order of occurrences. Search never rewrites it. |
| Cursor | Current occurrence and position. Cursor-only updates do not rewrite entries. |
| Visit history | Typed rows of actual visits, separate from planned cycle membership. |
| Manual baseline | The order to restore when Manual is applied after an automatic sort. |

Available songs show current library metadata. Unavailable songs keep their saved metadata and references. Controllers serialize transactions. Released migrations, IDs, references, grants and settings are preserved; a missing snapshot field stays unknown.

## Creating a queue

| Decision | Rule |
| --- | --- |
| Song tap | Default: snapshot and play from the tapped occurrence, keeping earlier songs. Alternatives: insert after current and play, add next, append. |
| Tap scope | One app-wide default, with optional overrides per song, album, artist, Album Artist, folder, playlist and search list. Explicit selection actions bypass tap preferences. |
| Names | Describe the source: `Album ◀ Artist`, `All Songs ◀ Artist`. Mixed-artist lists omit the suffix. |
| Mixed results | A queue from mixed search results holds only the displayed song occurrences, in order. |

### Reuse

Before creating a queue, look for one with the exact intended name, ordered song identities and per-occurrence playlist origins. Prefer the active match, otherwise the first in saved order. Play the requested occurrence from zero, and keep that queue's occurrence IDs, order and history.

Metadata, statistics and playback position do not distinguish queues. A different name, order, duplicate count or origin does. A source name is never inferred from an existing suffix, and saved queues are never merged.

## Managing queues

| Decision | Rule |
| --- | --- |
| Independence | Later edits to the source list do not change the queue. Order, duplicates, cursor and visit history survive restarts. |
| Restore | Restore paused unless an explicit play request is pending. Launch alone never starts playback. |
| Browsing | Selecting a queue browses it without changing playback, by default. Selecting an occurrence activates its queue. |
| Deletion | Deleting the active queue restores the most recently activated remaining queue and its cursor. Inactive edits leave playback intact. |
| Retention | Unlimited by default. At an optional limit, propose the oldest inactive queue by name and confirm. The active queue is protected. |

Activation recency is independent of manual queue order.

## Traversal

Repeat and Shuffle are global, persisted modes, initially off. `QueuePreferences` owns them.

- Shuffle visits occurrence IDs without repeating before exhaustion.
- Each traversal keeps visited membership, a history cursor and a concrete future. Transport, preloading and artwork share that future.
- Back and forward retrace actual visits, across repeat cycles and restarts.
- An explicit selection replaces retraceable forward history. Selection after exhaustion starts a new cycle.
- Projecting neighbours never records them as played.

Turning Shuffle off keeps the current occurrence, position and Repeat mode; next and previous then follow visible order. Turning it on starts a fresh traversal at the current occurrence. Reordering keeps shuffled plans, appended songs join them, and removals drop invalid visits.

### Play Next

Play Next inserts each batch immediately after the current occurrence, in visible order and in playback, ahead of shuffled forward history. A newer batch precedes earlier ones; each batch keeps its own order. A scheduled visit becomes visited only on arrival. Unplayed batches stay next-up after an explicit selection.

### Repeat

Advanced Repeat sets what happens when a song ends (Stop, Load next paused, Next, Repeat) and when a queue ends (Stop, Next queue, Repeat), with reset-to-first and reshuffle options. Retraced history and scheduled Play Next visits keep the song-end choice. Only advancing beyond the active traversal invokes queue-end behaviour.

## Sorting and the Manual baseline

Sorting a queue or playlist explicitly reorders the original occurrences, using current displayed metadata and statistics.

| Action | Effect on the baseline |
| --- | --- |
| Automatic sort | Keeps the Manual baseline. Applying Manual restores it. |
| Actual manual move, Randomize, Reverse | Replaces the baseline. A no-op move leaves it intact. |
| Revert | Restores the order and sort settings from when the options opened. Available only while the order differs. |

Randomize persists a new order. In the active queue the current occurrence goes first and the rest shuffle. Sorting and migration keep occurrence identities, cursor and traversal history. A failed save stays retryable. Later additions and removals are reconciled without restoring stale contents.

## Playlists

- Names are unique after trimming, ignoring case. Creation, rename and import reject duplicates without rewriting legacy duplicates.
- Add to playlists appends atomically to the chosen playlists and an optional new one.
- Imports accept local M3U, M3U8 and PLS files, resolved against indexed music. Ambiguous and unresolved entries are reviewed before skipping. Remote audio is never fetched.
- Import input is capped at 5 MiB and 20,000 entries, and commits after review.
- A custom cover is a required square crop stored privately. Replacement removes only unreferenced app-owned copies.

## Storage

The traversal persists a namespace and a real anchor token, so projected neighbours stay stable after restore or history removal without inventing visits. Media3 receives a bounded window of tokened visits in identity order, because the saved sequence already includes the shuffle. Legacy per-queue modes are imported once; their columns then stay inert.

## Tests

| Topic | Classes |
| --- | --- |
| Storage | `QueueDatabaseTest`, `PlaylistDatabaseTest`, `OrderedJourneyDatabaseTest`, `QueuePersistenceTest`, `QueueRestoreTest` |
| Creation and reuse | `QueueBrowsingTest`, `QueueReusePlaybackTest`, `QueueSaveOriginTest` |
| Traversal | `ShuffleModePlaybackTest`, `PlayNextJourneyTest`, `QueueJourneyPlaybackTest`, `QueueJourneyPersistenceTest`, `AdvancedRepeatTest` |
| Sorting | `BrowseSortRevertTest`, `RevertOrderPersistenceTest`, `ReverseOrderPersistenceTest`, `LiveOrderPersistenceTest`, `QueueRandomizePlaybackTest` |
| Playlists | `PlaylistCompletionTest`, `PlaylistCoverActionTest` |
| Upgrade compatibility | `ShuffleApkCompatibilityTest` |
