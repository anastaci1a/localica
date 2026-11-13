# Library

The library is a Room database of songs found in folders the user granted. A song's identity is its provider authority plus document ID, never a file path. Scans add and refresh songs but never lose what the app alone knows: dates, references and saved occurrences. Shared queries turn one immutable snapshot into every list.

- Removing a folder never deletes audio, and never removes saved playlist or queue entries.
- The cached library shows at once. Scans run in the background.
- Queries are deterministic: unknown values sort last, and ties are stable.
- Date added is owned by Localica. Audio files do not store it.
- Status: Implemented. The two-value date model (#930) is Released in v0.34.3; modification evidence in exports (#943) is Released in v0.34.4.


## Identity and roots

Root membership stores access URI and relative path separately from provider/document identity. Overlapping roots retain alternate grants; removal releases grants after commit but preserves saved unavailable occurrences. Moves retain identity only when the provider retains its ID. Playback rechecks live alternatives.

## Scans

Quick Scan inserts unindexed identities without refreshing existing rows. Full scan refreshes changed files and reconciles removals only after complete enumeration. Incomplete/cancelled listings preserve prior contents; metadata failures retain filename fallbacks. Include subfolders, exclusions and duration rules apply to both. Startup quick discovery completes before full refresh; [one lock](file-editing.md#the-lock) serializes scans and edits.

## Grouping

Albums use normalized title plus Album Artist, falling back to Artist. Artist and Album Artist remain separate; missing values form unknown groups. Folders use root/relative parent. Summaries use earliest year and known duration sums, marked when partial. Artist projections retain release classification/confidence, ranked songs, current-year releases and playlist membership; rescans populate newly supported tags.

## Queries and caches

Immutable snapshots prepare literal case-normalized substring indexes off main and share track lookups. Bounded grouping/query caches and statistics overlays reuse unchanged sources. BrowsePresentationCache publishes only matching source/query/style revisions. Warm-up stays off main; ambiguous ties or SQL-limit selections use canonical full reads. Queue snapshots always retain every displayed occurrence and selected index, including future paging.

## Dates

First seen records discovery once; imports/settings/recovery cannot change it. Date added starts equal to first seen but [library data](../library-data.md) imports may replace it. Rescans preserve both. This two-value model is part of v0.34.3 (#930); earlier releases used one value and could share a migration timestamp. Historical dates are never invented.

From v0.34.3, track rows outlive folder membership: removing and re-adding the same provider identity preserves both dates. Collections use earliest added/latest known modification of distinct members. Playlist creation/import dates stay fixed; rename, cover, membership and order change modification, while metadata refresh does not.

## Lyric sources

Local embedded synced/plain lyrics and same-basename granted-folder LRC sidecars remain selectable. Usable synced sources win; sidecars win equal timing types. Duplicate plain copies are hidden. Choices persist per song. Source/directory failures do not hide usable siblings or interrupt playback.

LRC supports offsets/repeated timestamps within 2 MiB/10,000 lines. ID3 SLT/SYLT supports milliseconds, not frame timing. Opus uses Media3 Vorbis comments. Word timing requires an actual supported source. Tree children/indexed folders resolve sidecars. [Artwork and lyrics](../interface/artwork-and-lyrics.md#lyrics) owns presentation.

## Tests

[LibraryDatabaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryDatabaseTest.kt).

[IncompleteScanTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/IncompleteScanTest.kt).

[StartupMergeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/StartupMergeTest.kt).

[LibraryPlaybackReadTest](../../app/src/test/java/io/github/anastaci1a/localica/library/LibraryPlaybackReadTest.kt).

[SearchSourceRevisionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SearchSourceRevisionTest.kt).

[BrowseRetentionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowseRetentionTest.kt).

[PinnedHomePreparationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinnedHomePreparationTest.kt).

[LibraryDataExchangeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryDataExchangeTest.kt).

[ArtistDataStorageTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistDataStorageTest.kt).

[LyricsReaderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsReaderTest.kt).

[EmbeddedTagsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/EmbeddedTagsTest.kt).
