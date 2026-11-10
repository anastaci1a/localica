# Product contract

## Scope

Localica plays audio stored on an Android device: Musicolet-inspired organization and an Apple Music-inspired interface. Core playback, editing, lyrics, and local statistics work offline without an account. Optional online metadata uses explicit consent.

Prioritize quick access, large libraries, background playback, accessibility, meaningful customization, and explicit, recoverable file operations. UI defaults live in [Interface](interface.md). Future capabilities live in [Roadmap](roadmap.md).

## Library and playlists

Combine user-selected folder trees. Overlapping roots share song identity while retaining alternate access paths. Removing a root never deletes audio or saved playlist/queue occurrences. Inaccessible songs remain visible.

Startup Quick Scan and Full scan are independently off by default. Show the cached library immediately. Enabled scans run in the background, respecting Include subfolders, exclusions and minimum duration. Quick Scan discovers new files while preserving existing metadata, dates and references. Full scan also refreshes changed files and reconciles removals.

With both enabled, finish quick discovery across all roots before full refresh. Discovery uses provider identity, not file timestamps, and may require enumerating every included directory.

Albums group by title plus Album Artist, falling back to track Artist. Normalize case and surrounding whitespace. Missing metadata forms distinct unknown groups. Artist views use track Artist. Folders group by root and relative parent directory. Keep Artist and Album Artist distinct, including compilations.

Shared queries provide deterministic search/filter/sort results. Unknown values sort last with stable ties. Collection summaries use earliest known year and summed known duration, marked when partial. Date/count rules are [below](#dates-and-listening-statistics).

Manual playlists support unique trimmed, case-insensitive names, ordered duplicate song occurrences, and reviewed local M3U/M3U8/PLS imports. Creation, rename and import reject duplicate names without rewriting legacy duplicates. Add to playlists atomically appends to selected existing destinations and an optional new named playlist.

Custom covers are required square crops stored privately. Replacement/deletion removes only unreferenced app-owned copies. Resolve imports against indexed music. Review ambiguous/unresolved entries before skipping them. Never fetch remote audio.

Available playlist songs use current library metadata. Unavailable songs retain saved metadata/references. Search never rewrites saved order. Queue/playlist sorting explicitly reorders original occurrences using current displayed metadata/statistics. Unavailable songs use saved metadata and current local counts.

Apply remains available when either the requested sort settings or resulting occurrence order differs. Subsequent dragging remains available in the complete, unfiltered list. Automatic sorts retain a Manual baseline. Applying Manual restores it. Actual manual moves, Randomize and Reverse replace that baseline, while no-op moves leave it intact. Reverse flips the current physical occurrence order.

Revert restores the physical order and Manual/sort settings from when the options popup opened, remaining available only while the reconciled physical order differs. Reconcile later additions/removals without restoring stale contents or metadata. Failed saves remain retryable. Preserve occurrence identities, cursor and traversal history through sorting and migration.

## Queues and playback

| Decision | Contract |
| --- | --- |
| Song tap | Default: snapshot the complete displayed song list in its filtered/sorted order and start at the tapped occurrence, retaining earlier songs. Alternatives: insert after current and play, add next, append. |
| Reuse | [Reuse rules](#reuse). |
| Tap scope | App-wide default with optional song/album/artist/Album Artist/folder/playlist/search overrides and explicit reset to inheritance. Text searches use the search override; explicit selection actions bypass tap preferences. |
| Independence | A queue is a listening snapshot, independent of later source-list edits. Preserve duplicate occurrence IDs, order, current occurrence, position and actual visit history across restarts. Restore paused unless an explicit playback request is pending; launch alone never autoplays. Legacy queues begin history at their known current song. |
| Browsing | Selecting a queue browses without changing playback by default. Alternatives: restore paused, resume, or preserve requested playing/paused state. Selecting an occurrence activates its queue. |
| Management | Rename/reorder queues and insert/remove/reorder entries. Names describe the source list: `Album ◀ Artist`, `All Songs ◀ Artist`; mixed-artist lists omit the suffix. |
| Deletion | Deleting the active queue restores the most recently activated remaining queue and its cursor; retain its requested playing/paused state through buffering or transient focus suppression; hard errors and completed playback remain stopped. Inactive edits/deletions leave playback intact. Activation recency is independent of manual order. |
| Retention | Unlimited by default. At an optional limit, propose the oldest inactive queue by name and confirm removal; allow alternatives/multiple choices and protect the active queue. Cancel leaves creation/playback unchanged. Support removing all except the current queue. |
| Previous | Below five seconds, go to the previous song; otherwise restart. Settings: threshold 1–60 seconds, always previous, always restart. Without a previous song under the active traversal, restart. Preserve paused/playing state across app/system/headset controls. |
| Continuity | Newer explicit Pause or Clear supersedes pending Play through readiness, suspended activation and temporary edit holds. Keep native focus suppression; it is not a manual Pause. Preload roughly five seconds of the actual next song under repeat/shuffle, enabled by default. Preserve intentional silence and encoder timing; no overlapping audio. Format/device limits are in [Build](build.md#compatibility-and-dependencies). |

### Reuse

Before creating or proposing retention removals, reuse a queue with the exact intended name, ordered song identities and per-occurrence playlist origins. Prefer the active match, otherwise the first in saved queue order. Select/play the requested occurrence from zero, retaining occurrence IDs, saved order and existing history under normal selection rules.

Metadata, statistics and playback position do not distinguish collections. Different names, orders, duplicate counts or origins remain distinct; new queues keep their intended names even when shared. Never infer a source name from an existing suffix or merge saved queues.

### Repeat and shuffle

Repeat and Shuffle are global persisted modes, initially off:

Shuffle traverses occurrence IDs without changing visible order or repeating before exhaustion.

Each shuffle traversal retains visited membership, history cursor and concrete future, shared by transport, preloading and artwork. Back/forward retrace actual visits across repeat cycles and restarts. Explicit selection replaces retraceable forward history without re-enabling visited occurrences. Selection after exhaustion starts a new cycle.

Turning Shuffle off preserves the current occurrence, position and Repeat mode. Next, Previous and autoplay immediately follow the saved visible queue order, including after Back through shuffled history. Projecting neighbors never records them as played.

Turning Shuffle on starts a fresh traversal at the current occurrence, without moving playback or clearing listening statistics. History retracing applies within that traversal, not across mode resets. Reordering preserves shuffled plans, appended songs join them, and removals drop invalid visits.

Play Next inserts each requested batch immediately after the current occurrence in both visible order and playback, including before shuffled forward history. Newer batches precede earlier requests; each batch retains its requested order. Keep occurrence identities, current visit and position. Scheduled visits become visited only on arrival and survive saved restoration.

Unplayed Play Next batches remain next-up after explicit selection or an in-flight decoder arrival. Inserting while retracing an earlier cycle schedules into the active traversal cycle without repeating there. With no current occurrence, the first inserted item becomes current and the rest remain next-up.

**Randomize queue order** persists a new order and displays Manual ascending. In the active queue, playing or paused, put the current occurrence first and shuffle the rest. Inactive queues shuffle fully. Preserve occurrence IDs, duplicates, cursor and playback state.

[Mutable-list presentation](interface.md#overrides-and-mutable-lists) owns viewport movement after reorder operations.

Advanced Repeat supports song-end Stop / Load next paused / Next / Repeat and queue-end Stop / Next queue / Repeat, with reset-to-first and reshuffle options. The player's [action controls](interface.md#player-actions) expose these settings. Retraced history and scheduled Play Next visits retain the song-end choice; only advancing beyond the active traversal invokes queue-end behavior.

## Metadata and artwork editing

Read/edit Title, Album, Artist, Album Artist, Year, Disc number, Track number, Comment, Genre, Composer, Lyricist, and embedded artwork. Read file modification dates without editing them. Carry metadata through grouping, queries, saved references, and information views.

Individual and bulk edits change only checked fields. Blank checked values remove user values while retaining track/disc totals and technical comments. Read and verify positive track/disc numerators and user-only comments. Preserve unchecked/mixed values and deduplicate physical songs for writes.

Request write grants explicitly, preview/review changes, verify writes, retain recovery, and report per-file outcomes. Unsupported formats/fields/providers remain playable and read-only with an explanation. Never silently substitute app-only tags.

Reversible batches retain full original files until Done or verified cancellation. Cancel waits for the acquired file transaction, restores every touched original, and verifies complete file hashes. Failed restoration retains recovery and never reports cancellation complete.

Check document sizes and app-files free space before promising undo. Insufficient space offers Save without undo or Back. Explicit no-undo saves retain current-file recovery and offer Stop after current; previous successful files cannot be reverted together.

Interrupted batches require an explicit Resume or Restore choice. A recorded cancellation remains restore-only. If cancellation cannot be recorded, refuse further writes in that session and explain that restarting cannot recover the unwritten choice. Retain the patch, copied replacement artwork, and editor draft for recovery.

A song awaiting byte recovery stays unavailable to playback, including startup and preload. Keep its occurrences/history. Stop at its boundary without silently skipping or wrapping. Unrelated songs remain playable when the affected source is known. Keep the applicable recovery notice through unrelated metadata/settings changes until dismissed, recovered, or playback moves elsewhere.

Manual recovery loads the restored song paused unless a newer Play is requested during that recovery. Metadata-refresh retries do not block verified safe bytes.

Artwork can be replaced or removed. Offer readable recent device/previously selected images plus a full files/gallery picker. Handle limited/revoked grants without requiring broad photo access. Every newly chosen image passes through a required square pan/zoom crop before review. Retain orientation and allow existing non-square embedded covers to display normally.

Export crops at up to 2048 pixels. Preserve the chooser, crop geometry, review and field drafts across Activity recreation for the same edit session. a new session or target starts fresh. Preview before applying. The player's artwork action edits only its current song. Album-wide changes require explicit bulk selection.

Implementation and supported-write limitations: [Structure](structure.md#file-operations).

## Lyrics sources

Read embedded synced/plain lyrics and same-basename `.lrc` sidecars in the song's actual granted folder. Keep distinct sources selectable without a duplicate plain rendition of a synced source. Prefer usable synced lyrics, then sidecars over embedded sources of equal timing type. A failed source must not hide usable siblings. Directory failures affect only that location.

Missing, malformed, inaccessible, or untimed lyrics must not interrupt playback or invent timestamps. Viewing is local, with no online lookup.

LRC supports offsets/repeated timestamps. ID3v2.2 SLT and ID3v2.3/2.4 SYLT support millisecond timing. Frame-count SYLT is unsupported. Use word timing only when a supported source actually supplies it. Presentation and gesture rules: [Artwork and lyrics](interface.md#artwork-and-lyrics).

Timed lyrics interpolate Media3's latency-aware position between samples, freezing during pause/buffering and bounding stale extrapolation. A persisted −2000…+2000 ms correction (50 ms steps. Positive means later) changes only lyric highlighting, never seek targets or audio. Do not add a guessed Bluetooth delay: [Media3 already tracks available output timestamps/latency](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java).

[Android timestamps can be unavailable for a route](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)). Physical-device calibration remains necessary for inaccurate outputs.

## Dates and listening statistics

| Value | Rule |
| --- | --- |
| Song added date | First successful indexing; retained on rescan. Existing migrated songs use migration date. Removing and reindexing starts a new date. A [library data](library-data.md) import can replace it; rescans keep the imported date. |
| Collection dates | Albums/Artists/Album Artists/Folders use earliest added and latest known modification among current distinct songs. |
| Playlist dates | Creation/import date; modification changes on rename, cover or membership/order edits, not metadata refresh or no-ops. Migrated playlists initially use creation for both. |
| Qualified play | Actual listening for 30 seconds or half the duration, whichever is shorter, once per traversal. Exclude paused time and seek jumps. |
| Traversal | Pauses resume it; automatic repeats, occurrence switches, and explicit restarts at zero begin another. Tag edits preserve the same visit and exclude editing time. Unqualified partial listening may be lost on process death. |
| Aggregation | Collections sum current distinct songs' counts. Playlist counts follow each queue occurrence's originating playlist, even after queue switches; legacy unknown origins remain unknown. |
| Retention | Qualified plays are local, all-time, durable, and independent of folder/queue deletion. Never invent historical dates or counts. Most played uses selected per-song counts, highest first. Longest listened weights each selected count by the song duration (unknown counts as zero); collections, playlists included, sum current distinct songs. |

## Online features and artist photos

Online features is one app-level consent switch. It defaults off. Settings groups this switch, Last.fm, and Artist photos under Online features. Search finds their individual controls. Consent covers artist photos and manual Last.fm sync. Turning it off prevents new network requests, hides artist photos and restores local play counts. Cached photos, the Last.fm archive and match choices are retained.

Artist and Album Artist rows, gallery tiles, Home pins, and the artist page show album art immediately. With consent on, a cached or fetched artist photo crossfades over it. Turning consent off immediately restores album art, including when a photo is cached.

Artist photo lookup sends artist and release names to Deezer, then MusicBrainz, Wikidata and Wikimedia Commons when needed. It never uses Last.fm images. Automatic matching requires a normalized artist name and a release title shared with the local library. Missing matches retain the caller's latest album artwork.

Photos refresh after 30 days. Failed automatic attempts wait 24 hours before retrying. Artist photos settings offers 128, 512, 1024 and 2048 MiB cache budgets; the default is 512 MiB. Usage and separately retained chosen photos are shown. Eviction follows last display and protects visible photos. Clear cache preserves chosen photos and match choices.

Acquisition continues through WorkManager after leaving the page or app. The persistent queue resumes interrupted artists after process death. Settings shows waiting artists, the current artist and retryable failures. Consent revocation cancels work; enabling it resumes pending artists. No library audio or local listening history is uploaded.

The artist menu opens a progressive thumbnail grid with current and pending choices. A failed pick keeps the previous photo and offers Retry on its tile. Clear suppresses automatic lookup until a deliberate re-pick or reset. Artist photo information shows saved Wikimedia attribution and licence information, which Commons pictures require. Photos never change audio files or embedded artwork.

## Optional Last.fm statistics

Settings → Online features → Last.fm accepts a username and explicitly starts a read-only sync. No password, account login or OAuth is used. Last.fm requests use its HTTPS API. Requests fetch listening statistics; they never upload local history or audio. Sync starts explicitly, with no automatic launch request.

Background continuation defaults on and uses unique scheduled work with a progress notification.

A refresh publishes one complete snapshot only after every request succeeds. Existing accounts use recent scrobbles and authoritative per-song user counts; large change sets fall back to full paging. Full rescan is also available explicitly. Requests are paced with retry backoff and a one-minute attempt limit. Failure preserves the archive; another account cannot use its counts.

Local qualified events remain separate. Use Last.fm counts defaults off. When enabled, the archive supplies per-song sorting and all-time rankings, including authoritative zeroes. Songs without a remote match and recent time ranges use local counts. Playlist origin counts always remain local. Matching the archive against the library is kept across launches and repeated only when the library, archive or match choices change; review lists are rebuilt when something displays them, never while browsing.

Qualified local plays after a successful sync temporarily add to the archived base. Each recording's addition appears on its matching edition copies. The next successful refresh replaces the base and clears temporary additions; failure preserves both. Sync never changes local play history. Legacy archives adopt the current local counter boundary once, without a network request.

Matching combines artist, album, ordered title tokens, available duration and recording MusicBrainz IDs. It recognizes artist/title credits inside uploader titles and removes descriptive upload decorations. Conflicting IDs, musical versions and substantial duration differences prevent automatic text matches. Copies of the same recording across editions show that version's plays; aggregate totals count each remote recording once.

Clear matches resolve silently. Probable matches expose their tier and reason for judgment-call review. Uncertain candidates need a decision, ordered by plays. Entries without plausible candidates stay outside the review count and are reconsidered when the library changes. Manual choices survive refreshes; resetting them leaves the archive intact.

Turning Last.fm or Online features off cancels syncing and stops using external counts while retaining the archive and choices. Re-enabling restores them without a request. Disconnect forgets the active connection and keeps its archive. Only confirmed Delete synced Last.fm data removes the archive and choices. Local history, playlists and audio tags are never deleted by these actions.

Builds without an application API key disable syncing and external counts with an explanatory settings message. Build configuration is documented in [Build](build.md#lastfm-build-key).
