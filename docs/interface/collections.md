# Collection pages

Album, artist, Album Artist and playlist pages share one layout: a full-width artwork header that fades into the song list, a Play/Shuffle pill, Sort/View at the top right and collection actions at the bottom right. Every action on the page uses the list as displayed, in its displayed order.

- The header appears at once from the cached cover, then sharpens.
- Play and Shuffle act on the displayed songs.
- Artist pages add an album filter and Group by album.
- Back first returns to the page top, then leaves.
- Status: Implemented.

## Header

The artwork header begins below the status bar, with a blurred extension above and a feathered colour continuation behind the songs. Artwork travels at 65% of content speed. Reduced motion disables the parallax.

- Foregrounds are black or white, chosen from the artwork's brightness.
- Back and collection overflow are glass controls.
- The background uses the cached cover and its colours immediately. Higher-resolution artwork and colour crossfade together.
- The header enters with the completed catalog. Tracks use the shared [loading reveal](browsing.md#loading).
- An empty list shows a quiet centred icon.

The floating return arrow aligns the Play/Shuffle pill's bottom with the Back button's bottom. System Back returns to the full page top in one ease; the next Back leaves.

## Play and Shuffle

One combined glass pill acts on the displayed list. Shuffle randomizes the new queue's saved order. It does not enable the global Shuffle mode.

## Albums

- Default view is Tracklist, in ascending disc and track order. Sort/View and pinch offer the alternatives.
- The header shows album and artist, and a large Play control that follows the configured song-tap behaviour.
- Smallest, Small and Tracklist rows show muted track numbers. Larger views show covers without numbers.
- The overflow shares playlist collection actions, without playlist-only edits.

## Artists and Album Artists

The header follows the first displayed song, including the album filter. Contents default to Year, ascending.

### Album filter

Below the header, a horizontal album row filters the list. It is in the same order as the songs' album groups.

| Input | Result |
| --- | --- |
| Short tap | Focuses only that album. |
| Long press | Toggles that album, with one haptic and no trailing tap. |
| All albums chosen, or the last one removed | Returns to All, which highlights All albums and every cover. |

A short-tapped single album stays explicitly focused, even for a one-album artist. Membership is reconciled after library changes. When filtering shortens the list, only the excess scroll eases to the new range.

### Group by album

**Group by album** sits below the sort controls. It is one shared artist-view preference, on by default.

| Setting | Sort orders | Inside an album |
| --- | --- | --- |
| On | Albums only. | Disc and track, ascending. |
| Off | Individual songs across all albums. | Not grouped. The album row keeps album order. |

Album aggregates: Year and Added use the earliest known value, Modified the latest, Duration and play counts the sum over distinct tracks. Missing aggregates sort last. Ties use album title, then album ID.

Sort, grouping, filter and library changes glide surviving rows with the shared keyed reorder. Reduced motion and nonblank search text reorder without it.

## Playlists

The header uses the manual cover or the canonical automatic cover, independent of displayed sort and filter. The bottom-right menu holds playlist edits: image, rename and delete. The image action sets a custom cover when absent and removes it when present. Scoped search is optional in View.

## Add to playlists

The panel combines existing playlists, shown with covers, and an inline field for a new name. Add, or keyboard submit, applies every chosen destination at once. A duplicate name disables Add. Existing membership is marked accessibly, and duplicates are allowed. Naming rules are in [Queues](../architecture/queues.md#playlists).

## Tests

| Topic | Classes |
| --- | --- |
| Pages and headers | `CollectionDetailTest`, `CollectionHeaderDensityContractTest`, `PlaylistDetailPresentationTest`, `PlaylistDestinationUiTest` |
| Artist albums | `ArtistAlbumSelectionTest`, `ArtistAlbumSortOrderTest`, `ArtistAlbumFilterRangeTest` |
