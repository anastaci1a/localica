# Library

The library is a Room database of songs found in folders the user granted. A song's identity is its provider authority plus document ID, never a file path. Scans add and refresh songs but never lose what the app alone knows: dates, references and saved occurrences. Shared queries turn one immutable snapshot into every list.

- Removing a folder never deletes audio, and never removes saved playlist or queue entries.
- The cached library shows at once. Scans run in the background.
- Queries are deterministic: unknown values sort last, and ties are stable.
- Date added is owned by Localica. Audio files do not store it.
- Status: Implemented. The two-value date model (#930) lands in v0.34.3.

## Identity and roots

| Fact | Rule |
| --- | --- |
| Identity | Provider authority + document ID. A document ID is never read as a filesystem path. |
| Root membership | Stores the access URI and the relative path separately. |
| Overlapping roots | Share one song identity and keep alternate access paths. |
| Moves and renames | Keep identity only if the provider keeps its ID. |
| Removed root | Songs leave browsing. Saved occurrences keep their metadata and stay visible as unavailable. |
| Grants | Removed grants are released after commit. Playback rechecks live alternate grants. |

## Scans

Two scans exist. Each can also run at startup; both startup switches default to off.

| Scan | Does | Never does |
| --- | --- | --- |
| Quick Scan | Enumerates permitted folders and reads metadata only for unindexed identities. Inserts additions in one transaction. | Overwrite cached rows, dates or references. |
| Full scan | Also refreshes changed files and reconciles removals. Replaces a root only after a complete listing. | Replace a root from an incomplete, loading or failed listing. |

- With both startup scans enabled, quick discovery finishes across all roots before the full refresh.
- Discovery uses provider identity, not file timestamps. It may need to enumerate every included directory.
- Scans respect Include subfolders, exclusions and minimum duration.
- Scans serialize with file edits through one [file-operation lock](file-editing.md#the-lock).
- Cancellation and errors keep the prior contents. A metadata failure keeps a filename fallback.

## Grouping

- Albums group by title plus Album Artist, falling back to track Artist. Case and surrounding whitespace are normalized.
- Missing metadata forms distinct unknown groups.
- Artist views use track Artist. Artist and Album Artist stay distinct, compilations included.
- Folders group by root and relative parent directory.
- Collection summaries use the earliest known year and the summed known duration, marked when partial.

The artist data source prepares release groups, five ranked songs, the current year's latest releases and playlist membership. Release-type and compilation tags survive storage. Classification reports tagged, heuristic or uncertain confidence. Existing libraries gain tags on a full rescan.

## Queries and caches

A snapshot load prepares an immutable search-text index off the main thread and reuses unchanged entries after a commit. Search matches literal case-normalized substrings and keeps occurrence order. There is no persistent search database.

| Owner | Role |
| --- | --- |
| Library snapshot | Immutable. Browse and playlist projections share one lazy, thread-safe current-track lookup per snapshot. |
| Grouping plans | Bounded order and membership plans per metadata source. Count-dependent ordering always stays fresh. |
| Statistics overlay | Prepared off the main thread. Reuses source tracks whose counts match. |
| `BrowsePresentationCache` | Bounded completed queries. Waits for the route's catalog sources and publishes only matching source, query and style revisions. |

Upcoming tab and queue queries are warmed off the main thread. A selection that exceeds SQL binding limits, or has ambiguous sort ties, uses the canonical full read. Queue creation receives every displayed occurrence and the selected index. Future paging must keep complete queue snapshots.

## Dates

| Value | Rule |
| --- | --- |
| First seen | Written once when a song is first discovered. Never changed by scans, settings, imports or recovery. From v0.34.3. |
| Date added | What lists show and sort by. Starts equal to first seen. A [library data](../library-data.md) import can replace it; rescans keep the imported date. |
| Removed, then indexed again | Starts a new date. |
| Collection dates | Albums, artists, Album Artists and folders use the earliest added and latest known modification among current distinct songs. |
| Playlist dates | Creation or import date. Modification changes on rename, cover, membership or order edits, not on metadata refresh. |

Before v0.34.3 one value served both roles, and songs present at the migration that introduced it share that migration's date. Localica never invents a historical date.

## Lyric sources

Lyrics are read locally, with no online lookup.

- Sources: embedded synced or plain lyrics, and a same-basename `.lrc` sidecar in the song's granted folder.
- Priority: usable synced lyrics first, then a sidecar over an embedded source of equal timing type.
- Distinct sources stay selectable. A plain copy of a synced source is not listed twice. The choice is saved per song.
- A failed source never hides usable siblings. A directory failure affects only that location.
- Missing, malformed or untimed lyrics never interrupt playback, and timestamps are never invented.

| Format | Support |
| --- | --- |
| LRC | Offsets and repeated timestamps. Input capped at 2 MiB and 10,000 lines. |
| ID3v2.2 SLT, ID3v2.3/2.4 SYLT | Millisecond timing. Frame-count SYLT is unsupported. |
| Opus | Through Media3's Vorbis-comment reader. |
| Word timing | Used only when a supported source supplies it. |

Sidecars resolve through tree children and indexed relative folders. Presentation and timing offset are in [Artwork and lyrics](../interface/artwork-and-lyrics.md#lyrics).

## Tests

| Topic | Classes |
| --- | --- |
| Database and scans | `LibraryDatabaseTest`, `IncompleteScanTest`, `StartupMergeTest`, `LibraryPlaybackReadTest` |
| Queries and caches | `SearchSourceRevisionTest`, `BrowseRetentionTest`, `PinnedHomePreparationTest` |
| Dates and exchange | `LibraryDataExchangeTest` |
| Artist data | `ArtistDataStorageTest` |
| Lyrics | `LyricsReaderTest`, `EmbeddedTagsTest` |
