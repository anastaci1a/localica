# Online features

Localica works fully offline. Online features is one consent switch, off by default, that covers two optional things: artist photos and read-only Last.fm statistics. Turning it off stops every new request at once. Nothing the user owns is ever uploaded: no audio, no local listening history. What was fetched is kept until the user deletes it.

- Requests use fixed HTTPS hosts, with redirects disabled, bounded responses and shared pacing.
- Fetched data never changes audio files, tags or local play history.
- Turning consent off hides photos and restores local counts. Caches, the archive and match choices stay.
- Consent stays closed until its saved value has loaded.
- Status: Implemented. Appears On and Similar Artists return empty through an enrichment interface.


## Artist photos

### Lookup

Artist/release names go to Deezer, then MusicBrainz, Wikidata and Commons as needed. Automatic matches require normalized names plus a shared release. Uncertain matches retain album art; Last.fm images are excluded. Requests cap metadata at 512 KiB, images at 4 MiB and search at five artists/25 releases. Refresh waits 30 days; failed attempts wait 24 hours durably.

### Display

A background-loaded memory index provides constant-time composition lookups. Unknown availability or pending decode uses a neutral placeholder; confirmed absence uses album art. Cached photos appear on first frame, new photos reveal normally. Revoked consent restores album art immediately. Display tests cover rows, tiles, Home and headers.

### Storage and work

WebP images cap at 1080 px plus 256 px thumbnails. Cache budgets are 128/512/1024/2048 MiB, default 512; last-display eviction protects visible images. Chosen photos/choices survive cache clearing. Atomic replacement and a promotion journal recover interrupted picks.

One unique WorkManager chain persists artist work across process death. New picks supersede only their generation; consent revocation cancels work. The chooser preserves the previous photo on failure and offers Retry. Clear suppresses automatic lookup until deliberate re-pick. Commons attribution/licences remain available.

## Last.fm

A username starts explicit read-only sync without passwords/OAuth. Background continuation defaults on. Missing build keys disable syncing with an explanation; see [Build](../development/build.md#lastfm-build-key).

### Sync

Publish one complete snapshot only after every request succeeds. Recent scrobbles and authoritative counts support incremental refresh; large changes fall back to full paging. Paced retries have a one-minute attempt limit. Failure preserves the archive, isolated by account. Atomic archive data and a separate summary cache keep cached opening independent of parsing.

### Counts

External counts default off. When enabled, matched all-time songs use authoritative archive counts, including zero; unmatched songs/recent windows use local counts. Playlist-origin counts remain local. Qualified plays since sync temporarily augment the archived base until successful refresh replaces it. Local history is never rewritten.

### Matching

Artist, album, ordered title tokens, duration and recording IDs determine matches. Conflicting IDs/versions/durations block automatic text matches. Clear matches resolve silently; probable matches expose reasons; uncertain candidates require decisions. No-candidate entries stay outside review until the library changes. Edition copies share recording counts; totals count each remote recording once. Manual choices survive refreshes.

### Off, disconnect and delete

Off cancels requests/use but retains archive and choices. Disconnect forgets the connection while keeping its archive. Only confirmed Delete removes synced data. None changes local history, playlists or tags. Worker/storage tests specify retries, replacement and restoration.

## Tests

[OnlineFeaturesSettingsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/OnlineFeaturesSettingsTest.kt).

[ArtistPhotoDisplayTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoDisplayTest.kt).

[ArtistPhotoWorkTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoWorkTest.kt).

[ArtistPhotoChooserTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoChooserTest.kt).

[ArtistPhotoStorageTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoStorageTest.kt).

[ArtistPhotoEncodingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoEncodingTest.kt).

[ArtistPhotoFirstFrameTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistPhotoFirstFrameTest.kt).

[LastFmWorkerTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmWorkerTest.kt).

[LastFmArchivePullTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmArchivePullTest.kt).

[LastFmMetadataStorageTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmMetadataStorageTest.kt).

[LastFmCachedOpenTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmCachedOpenTest.kt).

[LastFmPreparationThreadTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmPreparationThreadTest.kt).
