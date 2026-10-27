package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistHeroTest {
    private val first = Track("stored:first", "Zulu", id = "first", fileName = "first.mp3", relativePath = "first.mp3")
    private val second = Track("stored:second", "Alpha", id = "second", fileName = "second.mp3", relativePath = "second.mp3")
    private val playlist = ManualPlaylist(id = "mix", name = "Mix", entries = listOf(
        SongRow("occurrence-first", first), SongRow("occurrence-second", second), SongRow("duplicate-first", first)))

    @Test fun automaticHeroMatchesTheCanonicalCardRatherThanSortedOrFilteredRows() {
        val snapshot = LibrarySnapshot(tracks = listOf(first.copy(uri = "current:first"), second))
        val card = BrowseQueries.evaluate(snapshot, listOf(playlist), BrowseRoute(MainTab.Playlists)).items.single()
        val displayed = BrowseResult(items = listOf(BrowserItem(MediaRef(MediaKind.Song, second.id), second.title,
            null, listOf(playlist.entries[1]), playlist.entries[1])))
        assertEquals("stored:second", collectionHeroUri(displayed, card))
        assertEquals(card.coverUris.single(), playlistHeroUri(playlist, snapshot))
        assertEquals("current:first", playlistHeroUri(playlist, snapshot))
        assertEquals("stored:first", playlistHeroUri(playlist, LibrarySnapshot()))
        assertEquals("stored:second", playlistHeroUri(playlist.copy(entries = playlist.entries.drop(1)), snapshot))
        assertEquals(listOf("occurrence-first", "occurrence-second", "duplicate-first"), playlist.entries.map { it.id })
    }

    @Test fun manualCoverWinsIncludingEmptyAndClearingAnEmptyPlaylistClearsItsHero() {
        val snapshot = LibrarySnapshot(tracks = listOf(first, second))
        assertEquals("manual:cover", playlistHeroUri(playlist.copy(coverUri = "manual:cover"), snapshot))
        val empty = playlist.copy(entries = emptyList(), coverUri = "manual:cover")
        assertEquals("manual:cover", playlistHeroUri(empty, snapshot))
        assertNull(playlistHeroUri(empty.copy(coverUri = null), snapshot))
        assertNull(playlistHeroUri(null, snapshot))
    }
}
