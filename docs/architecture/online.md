# Online features

Localica works fully offline. Online features is one consent switch, off by default, that covers two optional things: artist photos and read-only Last.fm statistics. Turning it off stops every new request at once. Nothing the user owns is ever uploaded: no audio, no local listening history. What was fetched is kept until the user deletes it.

- Requests use fixed HTTPS hosts, with redirects disabled, bounded responses and shared pacing.
- Fetched data never changes audio files, tags or local play history.
- Turning consent off hides photos and restores local counts. Caches, the archive and match choices stay.
- Consent stays closed until its saved value has loaded.
- Status: Implemented. Appears On and Similar Artists return empty through an enrichment interface.

## Artist photos

### Lookup

Lookup sends artist and release names to Deezer, then MusicBrainz, Wikidata and Wikimedia Commons when needed. It never uses Last.fm images. An automatic match needs a normalized artist name and a release title shared with the local library. Without a match, album artwork stays.

| Limit | Value |
| --- | --- |
| Metadata response | 512 KiB |
| Picture | 4 MiB |
| Search breadth | Five artists and 25 releases per candidate. Incomplete coverage falls back conservatively. |
| Refresh | After 30 days. A failed automatic attempt waits 24 hours, across process death. |

Provider errors become fixed failures without URLs or transport messages.

### Display

With consent on, artist and Album Artist items use the decoded cached photo on their first frame.

| State | Shown |
| --- | --- |
| Photo availability unknown, or decode pending | The neutral placeholder. |
| Confirmed missing | Album art. |
| Newly fetched | The photo, with the normal reveal. |
| Consent off | Album art, even when a photo is cached. |

This applies to rows, gallery tiles, Home pins and collection headers. Startup loads photo metadata into a memory index off the main thread. Composition does constant-time lookups without touching the filesystem.

### Storage and work

- Images are re-encoded as WebP at display size, capped at 1080 pixels, plus a 256-pixel thumbnail.
- Cache budgets are 128, 512, 1024 or 2048 MiB; the default is 512 MiB. Eviction follows last display and protects visible photos.
- Chosen photos live with their choices, outside the evictable cache. Clear cache keeps them.
- Entries and consent use atomic file replacement. A promotion journal recovers an interrupted choice.
- One unique WorkManager chain, with a persistent per-artist queue, continues after leaving the app.
- A new pick supersedes an older generation without cancelling unrelated artists. Revoking consent cancels work.

The artist menu opens a progressive thumbnail grid. A failed pick keeps the previous photo and offers Retry. Clear suppresses automatic lookup until a deliberate re-pick. Commons pictures show their saved attribution and licence.

## Last.fm

Settings accepts a username and starts a read-only sync. No password, login or OAuth is used. Sync starts only on request; background continuation of a started sync defaults to on.

### Sync

- A refresh publishes one complete snapshot only after every request succeeds.
- Existing accounts use recent scrobbles and authoritative per-song counts. Large change sets fall back to full paging. Full rescan is also available.
- Requests are paced, with retry backoff and a one-minute attempt limit.
- A failure keeps the archive. Another account cannot use its counts.

The provider stores its snapshot, account, mappings and local counter baseline in one atomic file. A separate versioned summary cache supplies the last numbers before the archive loads. A cache failure never changes the archive.

### Counts

Local qualified plays stay separate. Use Last.fm counts defaults to off.

| Case | Count used |
| --- | --- |
| Setting on, song matched | The archive, including authoritative zeroes. |
| No remote match, or a recent time range | Local counts. |
| Playlist origin counts | Always local. |
| Plays since the last sync | Added temporarily to the archived base, until the next successful refresh replaces it. |

Sync never writes local play history.

### Matching

Matching combines artist, album, ordered title tokens, duration and recording MusicBrainz IDs. It recognizes credits inside uploader titles and removes upload decorations. Conflicting IDs, musical versions and large duration differences prevent an automatic text match.

| Tier | Handling |
| --- | --- |
| Clear | Resolved silently. |
| Probable | Shown with tier and reason, as a judgment call. |
| Uncertain | Needs a decision; ordered by plays. |
| No plausible candidate | Outside the review count; reconsidered when the library changes. |

Copies of one recording across editions each show that recording's plays; totals count it once. Matching is kept across launches and repeated only when the library, archive or choices change. Manual choices survive refreshes.

### Off, disconnect and delete

| Action | Effect |
| --- | --- |
| Turn Last.fm or Online features off | Cancels syncing and stops using external counts. Keeps the archive and choices. |
| Disconnect | Forgets the connection. Keeps the archive. |
| Delete synced Last.fm data (confirmed) | Removes the archive and choices. |

None of these delete local history, playlists or tags. A build without an API key disables syncing with a settings message; see [Build](../development/build.md#lastfm-build-key).

## Tests

| Topic | Classes |
| --- | --- |
| Consent | `OnlineFeaturesSettingsTest` |
| Artist photos | `ArtistPhotoDisplayTest`, `ArtistPhotoWorkTest`, `ArtistPhotoChooserTest`, `ArtistPhotoStorageTest`, `ArtistPhotoEncodingTest`, `ArtistPhotoFirstFrameTest` |
| Last.fm | `LastFmWorkerTest`, `LastFmArchivePullTest`, `LastFmMetadataStorageTest`, `LastFmCachedOpenTest`, `LastFmPreparationThreadTest` |
