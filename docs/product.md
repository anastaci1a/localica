# Product contract

## Scope

A dependable offline Android player for audio on the device: Musicolet-inspired organization and an Apple Music-inspired interface. Core playback, editing, lyrics, and statistics need no account or network permission. Prioritize quick access, large libraries, background playback, accessibility, meaningful customization, and explicit, recoverable file operations. UI defaults live in [Interface](interface.md); future capabilities live in [Roadmap](roadmap.md).

## Library and playlists

- Combine user-selected folder trees. Overlapping roots share song identity while retaining alternate access paths. Removing a root never deletes audio or saved playlist/queue occurrences; inaccessible songs remain visible.
- Albums group by title plus Album Artist, falling back to track Artist. Normalize case and surrounding whitespace; missing metadata forms distinct unknown groups. Artist views use track Artist; folders group by root and relative parent directory. Keep Artist and Album Artist distinct, including compilations.
- Shared queries provide deterministic search/filter/sort results. Unknown values sort last with stable ties. Collection summaries use earliest known year and summed known duration, marked when partial; date/count rules are [below](#dates-and-listening-statistics).
- Manual playlists support creation, rename, ordered duplicate occurrences, additions/removals, deletion, and reviewed local M3U/M3U8/PLS imports. Resolve imports against indexed music; review ambiguous/unresolved entries before skipping them. Never fetch remote audio.
- Available playlist songs use current library metadata; unavailable songs retain saved metadata/references. Display search/sort never rewrites saved order. Manual reordering requires the complete, unfiltered saved order.

## Queues and playback

| Decision | Contract |
| --- | --- |
| Song tap | Default: snapshot the complete displayed song list in its filtered/sorted order and start at the tapped occurrence, retaining earlier songs. Alternatives: insert after current and play, add next, append. |
| Tap scope | App-wide default with optional song/album/artist/Album Artist/folder/playlist/search overrides and explicit reset to inheritance. Text searches use the search override; explicit selection actions bypass tap preferences. |
| Independence | A queue is a listening snapshot, independent of later source-list edits. Preserve duplicate occurrence IDs, order, current occurrence, position and actual visit history across restarts. Restore paused; never autoplay on launch. Legacy queues begin history at their known current song. |
| Browsing | Selecting a queue browses without changing playback by default. Alternatives: restore paused, resume, or preserve playing state. Selecting an occurrence activates its queue. |
| Management | Rename/reorder queues and insert/remove/reorder entries. Names describe the source list: `Album ◀ Artist`, `All Songs ◀ Artist`; mixed-artist lists omit the suffix. |
| Deletion | Deleting the active queue restores the most recently activated remaining queue and its cursor; autoplay only if the deleted queue was playing. Inactive edits/deletions leave playback intact. Activation recency is independent of manual order. |
| Retention | Unlimited by default. At an optional limit, propose the oldest inactive queue by name and confirm removal; allow alternatives/multiple choices and protect the active queue. Cancel leaves creation/playback unchanged. Support removing all except the current queue. |
| Previous | Below five seconds, go to the previous song; otherwise restart. Settings: threshold 1–60 seconds, always previous, always restart. Without a previous visit, restart. Preserve paused/playing state across app/system/headset controls. |
| Continuity | Preload roughly five seconds of the actual next song under repeat/shuffle, enabled by default. Preserve intentional silence and encoder timing; no overlapping audio. Format/device limits are in [Build](build.md#compatibility-and-dependencies). |

Repeat and Shuffle are global persisted modes, initially off. Shuffle traverses occurrence IDs without altering visible order or repeating before exhaustion. Each queue retains its visited membership, history cursor and concrete future, shared by transport, preloading and artwork. Back/forward retrace the same visits, including across repeat cycles and restarts. Explicit selection replaces forward history without making already-visited occurrences eligible again; selecting after exhaustion starts a fresh cycle. Mode changes preserve history and reconcile future traversal; reordering preserves shuffled plans, additions join them, and removals drop invalid visits. **Randomize queue order** instead persists a new order, displays Manual ascending, and preserves the current occurrence/cursor/playing state.

Advanced Repeat supports song-end Stop / Load next paused / Next / Repeat and queue-end Stop / Next queue / Repeat, with reset-to-first and reshuffle options. The player's [action controls](interface.md#player-actions) expose these settings.

## Metadata and artwork editing

Read/edit Title, Album, Artist, Album Artist, Year, Disc number, Track number, Comment, Genre, Composer, Lyricist, and embedded artwork. Read file modification dates without editing them. Carry metadata through grouping, queries, saved references, and information views.

- Individual and bulk edits change only checked fields; blank checked values remove tags. Preserve unchecked/mixed values and deduplicate physical songs for writes.
- Request write grants explicitly, preview/review changes, verify writes, retain recovery, and report per-file outcomes. A batch can stop after its current transaction. Unsupported formats/fields/providers remain playable and read-only with an explanation; never silently substitute app-only tags.
- Artwork can be replaced or removed. Offer readable recent device/previously selected images plus a full files/gallery picker; handle limited/revoked grants without requiring broad photo access. Preview before applying. The player's artwork action edits only its current song; album-wide changes require explicit bulk selection.

Implementation and supported-write limitations: [Structure](structure.md#file-operations).

## Lyrics sources

Read embedded synced/plain lyrics and same-basename `.lrc` sidecars in the song's actual granted folder. Keep sources selectable: prefer usable synced lyrics, then sidecars over embedded sources of equal timing type. Missing, malformed, inaccessible, or untimed lyrics must not interrupt playback or invent timestamps. Viewing is local, with no online lookup.

LRC supports offsets/repeated timestamps; ID3 SYLT supports millisecond timing. Frame-count SYLT is unsupported. Use word timing only when a supported source actually supplies it. Presentation and gesture rules: [Artwork and lyrics](interface.md#artwork-and-lyrics).

## Dates and listening statistics

| Value | Rule |
| --- | --- |
| Song added date | First successful indexing; retained on rescan. Existing migrated songs use migration date. Removing and reindexing starts a new date. |
| Collection dates | Albums/Artists/Album Artists/Folders use earliest added and latest known modification among current distinct songs. |
| Playlist dates | Creation/import date; modification changes on rename or membership/order edits, not metadata refresh or no-ops. Migrated playlists initially use creation for both. |
| Qualified play | Actual listening for 30 seconds or half the duration, whichever is shorter, once per traversal. Exclude paused time and seek jumps. |
| Traversal | Pauses resume it; automatic repeats, occurrence switches, and explicit restarts at zero begin another. Unqualified partial listening lasts only while the service lives. |
| Aggregation | Collections sum current distinct songs' counts. Playlist counts follow each queue occurrence's originating playlist, even after queue switches; legacy unknown origins remain unknown. |
| Retention | Qualified plays are local, all-time, durable, and independent of folder/queue deletion. Never invent historical dates or counts. Most played shows qualified songs, highest count first. |
