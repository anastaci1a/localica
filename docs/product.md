# Product contract

## Scope

A dependable offline Android player for audio on the device: Musicolet-inspired organization and an Apple Music-inspired interface. Core playback, editing, lyrics, and statistics need no account or network permission. Prioritize quick access, large libraries, background playback, accessibility, meaningful customization, and explicit, recoverable file operations. UI defaults live in [Interface](interface.md); future capabilities live in [Roadmap](roadmap.md).

## Library and playlists

- Combine user-selected folder trees. Overlapping roots share song identity while retaining alternate access paths. Removing a root never deletes audio or saved playlist/queue occurrences; inaccessible songs remain visible.
- Albums group by title plus Album Artist, falling back to track Artist. Normalize case and surrounding whitespace; missing metadata forms distinct unknown groups. Artist views use track Artist; folders group by root and relative parent directory. Keep Artist and Album Artist distinct, including compilations.
- Shared queries provide deterministic search/filter/sort results. Unknown values sort last with stable ties. Collection summaries use earliest known year and summed known duration, marked when partial; date/count rules are [below](#dates-and-listening-statistics).
- Manual playlists support unique trimmed, case-insensitive names, ordered duplicate song occurrences, and reviewed local M3U/M3U8/PLS imports. Creation, rename and import reject duplicate names without rewriting legacy duplicates. Add to playlists atomically appends to selected existing destinations and an optional new named playlist. Custom covers are required square crops stored privately; replacement/deletion removes only unreferenced app-owned copies. Resolve imports against indexed music; review ambiguous/unresolved entries before skipping them. Never fetch remote audio.
- Available playlist songs use current library metadata; unavailable songs retain saved metadata/references. Search never rewrites saved order. Queue/playlist sorting is an explicit persisted reorder operation; subsequent dragging remains available in the complete, unfiltered list. Automatic sorts retain a Manual baseline; applying Manual restores it. Actual manual moves and Randomize replace that baseline, while no-op moves leave it intact. After Randomize, Revert restores the physical order and Manual/sort settings from when the options popup opened. Reconcile later additions/removals without restoring stale contents or metadata; failed saves remain retryable. Preserve occurrence identities, cursor and traversal history through sorting and migration.

## Queues and playback

| Decision | Contract |
| --- | --- |
| Song tap | Default: snapshot the complete displayed song list in its filtered/sorted order and start at the tapped occurrence, retaining earlier songs. Alternatives: insert after current and play, add next, append. |
| Tap scope | App-wide default with optional song/album/artist/Album Artist/folder/playlist/search overrides and explicit reset to inheritance. Text searches use the search override; explicit selection actions bypass tap preferences. |
| Independence | A queue is a listening snapshot, independent of later source-list edits. Preserve duplicate occurrence IDs, order, current occurrence, position and actual visit history across restarts. Restore paused unless an explicit playback request is pending; launch alone never autoplays. Legacy queues begin history at their known current song. |
| Browsing | Selecting a queue browses without changing playback by default. Alternatives: restore paused, resume, or preserve requested playing/paused state. Selecting an occurrence activates its queue. |
| Management | Rename/reorder queues and insert/remove/reorder entries. Names describe the source list: `Album ◀ Artist`, `All Songs ◀ Artist`; mixed-artist lists omit the suffix. |
| Deletion | Deleting the active queue restores the most recently activated remaining queue and its cursor; retain its requested playing/paused state through buffering or transient focus suppression; hard errors and completed playback remain stopped. Inactive edits/deletions leave playback intact. Activation recency is independent of manual order. |
| Retention | Unlimited by default. At an optional limit, propose the oldest inactive queue by name and confirm removal; allow alternatives/multiple choices and protect the active queue. Cancel leaves creation/playback unchanged. Support removing all except the current queue. |
| Previous | Below five seconds, go to the previous song; otherwise restart. Settings: threshold 1–60 seconds, always previous, always restart. Without a previous song under the active traversal, restart. Preserve paused/playing state across app/system/headset controls. |
| Continuity | Newer explicit Pause or Clear supersedes pending Play through readiness, suspended activation and temporary edit holds. Keep native focus suppression; it is not a manual Pause. Preload roughly five seconds of the actual next song under repeat/shuffle, enabled by default. Preserve intentional silence and encoder timing; no overlapping audio. Format/device limits are in [Build](build.md#compatibility-and-dependencies). |

Repeat and Shuffle are global persisted modes, initially off:

- Shuffle traverses occurrence IDs without changing visible order or repeating before exhaustion.
- During a shuffle traversal, Back/forward retrace actual visits across repeat cycles and restarts. Explicit selection replaces forward history without re-enabling visited occurrences; selection after exhaustion starts a new cycle.
- Turning Shuffle off preserves the current occurrence, position and Repeat mode. Next, Previous and autoplay immediately follow the saved visible queue order, including after Back through shuffled history; projecting neighbors never records them as played.
- Turning Shuffle on starts a fresh traversal at the current occurrence, without moving playback or clearing listening statistics. History retracing applies within that traversal, not across mode resets. Reordering preserves shuffled plans, additions join them, and removals drop invalid visits.
- **Randomize queue order** persists a new order and displays Manual ascending. In the actively playing queue, put the playing occurrence first and shuffle the rest; paused/inactive queues shuffle fully. Preserve occurrence IDs, duplicates, cursor and playback state.

Advanced Repeat supports song-end Stop / Load next paused / Next / Repeat and queue-end Stop / Next queue / Repeat, with reset-to-first and reshuffle options. The player's [action controls](interface.md#player-actions) expose these settings.

## Metadata and artwork editing

Read/edit Title, Album, Artist, Album Artist, Year, Disc number, Track number, Comment, Genre, Composer, Lyricist, and embedded artwork. Read file modification dates without editing them. Carry metadata through grouping, queries, saved references, and information views.

- Individual and bulk edits change only checked fields; blank checked values remove user values while retaining track/disc totals and technical comments. Read and verify positive track/disc numerators and user-only comments. Preserve unchecked/mixed values and deduplicate physical songs for writes.
- Request write grants explicitly, preview/review changes, verify writes, retain recovery, and report per-file outcomes. A batch can stop after its current transaction. Unsupported formats/fields/providers remain playable and read-only with an explanation; never silently substitute app-only tags.
- A song awaiting byte recovery stays unavailable to playback, including startup and preload. Keep its occurrences/history; stop at its boundary without silently skipping or wrapping. Unrelated songs remain playable when the affected source is known. Keep the applicable recovery notice through unrelated metadata/settings changes until dismissed, recovered, or playback moves elsewhere. Manual recovery loads the restored song paused unless a newer Play is requested during that recovery; metadata-refresh retries do not block verified safe bytes.
- Artwork can be replaced or removed. Offer readable recent device/previously selected images plus a full files/gallery picker; handle limited/revoked grants without requiring broad photo access. Every newly chosen image passes through a required square pan/zoom crop before review; retain orientation and allow existing non-square embedded covers to display normally. Export crops at up to 2048 pixels. Preserve the chooser, crop geometry, review and field drafts across Activity recreation for the same edit session; a new session or target starts fresh. Preview before applying. The player's artwork action edits only its current song; album-wide changes require explicit bulk selection.

Implementation and supported-write limitations: [Structure](structure.md#file-operations).

## Lyrics sources

Read embedded synced/plain lyrics and same-basename `.lrc` sidecars in the song's actual granted folder. Keep distinct sources selectable without a duplicate plain rendition of a synced source; prefer usable synced lyrics, then sidecars over embedded sources of equal timing type. A failed source must not hide usable siblings; directory failures affect only that location. Missing, malformed, inaccessible, or untimed lyrics must not interrupt playback or invent timestamps. Viewing is local, with no online lookup.

LRC supports offsets/repeated timestamps; ID3v2.2 SLT and ID3v2.3/2.4 SYLT support millisecond timing. Frame-count SYLT is unsupported. Use word timing only when a supported source actually supplies it. Presentation and gesture rules: [Artwork and lyrics](interface.md#artwork-and-lyrics).

Timed lyrics interpolate Media3's latency-aware position between samples, freezing during pause/buffering and bounding stale extrapolation. A persisted −2000…+2000 ms correction (50 ms steps; positive means later) changes only lyric highlighting, never seek targets or audio. Do not add a guessed Bluetooth delay: [Media3 already tracks available output timestamps/latency](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java); [Android timestamps can be unavailable for a route](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)). Physical-device calibration remains necessary for inaccurate outputs.

## Dates and listening statistics

| Value | Rule |
| --- | --- |
| Song added date | First successful indexing; retained on rescan. Existing migrated songs use migration date. Removing and reindexing starts a new date. |
| Collection dates | Albums/Artists/Album Artists/Folders use earliest added and latest known modification among current distinct songs. |
| Playlist dates | Creation/import date; modification changes on rename, cover or membership/order edits, not metadata refresh or no-ops. Migrated playlists initially use creation for both. |
| Qualified play | Actual listening for 30 seconds or half the duration, whichever is shorter, once per traversal. Exclude paused time and seek jumps. |
| Traversal | Pauses resume it; automatic repeats, occurrence switches, and explicit restarts at zero begin another. Unqualified partial listening lasts only while the service lives. |
| Aggregation | Collections sum current distinct songs' counts. Playlist counts follow each queue occurrence's originating playlist, even after queue switches; legacy unknown origins remain unknown. |
| Retention | Qualified plays are local, all-time, durable, and independent of folder/queue deletion. Never invent historical dates or counts. Most played shows qualified songs, highest count first. |
