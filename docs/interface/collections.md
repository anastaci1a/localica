# Collection pages

Album, artist, Album Artist and playlist pages share one layout: a full-width artwork header that fades into the song list, a Play/Shuffle pill, Sort/View at the top right and collection actions at the bottom right. Every action on the page uses the list as displayed, in its displayed order.

- The header appears at once from the cached cover, then sharpens.
- Play and Shuffle act on the displayed songs.
- Artist pages add an album filter and Group by album.
- Back first returns to the page top, then leaves.
- Status: Implemented.


## Header

Collection headers fade full-width artwork into songs, using cached covers/colours immediately and sharpening them together. Foreground polarity follows artwork brightness. Parallax moves at 65% of content speed and is disabled by Reduced motion. Tracks use the shared [loading reveal](browsing.md#loading).

Back first returns to the complete header, then leaves. The return arrow aligns the Play/Shuffle pill with Back. Header geometry and density cases belong to the linked presentation tests.

## Play and Shuffle

Both actions use displayed songs in displayed order. Shuffle randomizes a new queue's saved order without enabling global Shuffle. Album Play follows the configured song-tap behavior.

## Albums

Albums default to Tracklist in disc/track order. Dense views show track numbers; larger views use covers. Sort/View and pinch select alternatives. Collection menus share actions while excluding playlist-only edits.

## Artists and Album Artists

The header follows the first displayed song, including the album filter. Default order is Year ascending. Artist and Album Artist remain distinct identities.

### Album filter

The album row follows group order. Tap focuses one album; hold toggles membership without a trailing tap. Choosing all or removing the last selection restores All. Explicit single-album focus remains explicit. Library changes reconcile membership; shrinking content eases only excess scroll.

### Group by album

This shared artist preference defaults on. Grouped sorts order albums while songs retain disc/track order; ungrouped sorts order songs. Aggregates use earliest Year/Added, latest Modified and distinct-song duration/count sums. Unknowns sort last; ties use album title/ID.

Keyed reorders animate surviving rows unless Reduced motion or nonblank search disables motion.

## Playlists

Manual or canonical automatic covers are independent of displayed sort/filter. Playlist menus own image, rename and delete. Image sets a cover when absent and removes it when present. Scoped search is optional.

## Add to playlists

One draft combines existing destinations and an optional new name, then applies atomically. Duplicate names disable Add; existing membership is marked accessibly and song duplicates are allowed. [Queues](../architecture/queues.md#playlists) owns naming and storage rules.

## Tests

[CollectionDetailTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionDetailTest.kt).

[CollectionHeaderDensityContractTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionHeaderDensityContractTest.kt).

[PlaylistDetailPresentationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaylistDetailPresentationTest.kt).

[PlaylistDestinationUiTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlaylistDestinationUiTest.kt).

[ArtistAlbumSelectionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistAlbumSelectionTest.kt).

[ArtistAlbumSortOrderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistAlbumSortOrderTest.kt).

[ArtistAlbumFilterRangeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistAlbumFilterRangeTest.kt).
