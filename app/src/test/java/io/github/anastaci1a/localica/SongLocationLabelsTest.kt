package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.library.*
import org.junit.Assert.*
import org.junit.Test

class SongLocationLabelsTest {
    private fun root(id: String, vararg path: String) = SongRootName(id, path.toList())

    @Test fun singleRootOmitsItsName() {
        assertEquals(mapOf("a" to ""), songRootLabels(listOf(root("a", "Music"))))
    }

    @Test fun usesOnlyNecessaryVerifiedParentNames() {
        val roots = listOf(root("a", "Files", "1", "Media"), root("b", "Files", "2", "Media"), root("c", "Downloads"))
        assertEquals(mapOf("a" to "1/Media", "b" to "2/Media", "c" to "Downloads"), songRootLabels(roots))
    }

    @Test fun multipleParentsAndGlobalRootSetChanges() {
        val roots = listOf(root("a", "Files", "1", "Music", "Media"), root("b", "Files", "2", "Music", "Media"))
        assertEquals("1/Music/Media", songRootLabels(roots)["a"])
        assertEquals("", songRootLabels(roots.take(1))["a"])
        assertEquals("Media", songRootLabels(listOf(roots[0], root("b", "Renamed")))["a"])
    }

    @Test fun providerNamesAreNotUriDecodedOrNormalized() {
        val roots = listOf(root("opaque:secret", "Музыка 100%20"), root("other", "音楽"))
        assertEquals("Музыка 100%20", songRootLabels(roots)["opaque:secret"])
        assertFalse(songRootLabels(roots).values.any { it.contains("opaque") })
    }

    @Test fun realProviderLabelsDisambiguateUnavailableAncestors() {
        val roots = listOf(SongRootName("secret-a", listOf("Media"), "Files"), SongRootName("secret-b", listOf("Media"), "Cloud"))
        assertEquals(mapOf("secret-a" to "Media (Files)", "secret-b" to "Media (Cloud)"), songRootLabels(roots))
        assertFalse(songRootLabels(roots).values.any { it.contains("secret") })
    }

    @Test fun opaqueFallbackOrdinalsAreStableForTheRootSetAndNeverExposeIds() {
        val roots = listOf(root("opaque-c", "Media"), root("opaque-a", "Media"), root("opaque-b", "Media"))
        val labels = songRootLabels(roots)
        assertEquals("Media · Folder 1", labels["opaque-a"])
        assertEquals("Media · Folder 2", labels["opaque-b"])
        assertEquals("Media · Folder 3", labels["opaque-c"])
        assertEquals(labels, songRootLabels(roots.reversed()))
        assertEquals("Media · Folder 2", songRootLabels(roots.filter { it.uri != "opaque-b" })["opaque-c"])
        assertEquals("", songRootLabels(roots.take(1))["opaque-c"])
        assertFalse(labels.values.any { it.contains("opaque") })
    }

    @Test fun sameProviderAndFallbackLikeRealNamesStillStayGloballyUnique() {
        val roots = listOf(SongRootName("a", listOf("Media"), "Files"), SongRootName("b", listOf("Media"), "Files"),
            root("c", "Media (Files) · Folder 1"), root("d", "Other"))
        val labels = songRootLabels(roots)
        assertEquals(4, labels.values.toSet().size)
        assertEquals("Other", labels["d"])
        assertEquals(labels, songRootLabels(roots.reversed()))
    }

    @Test fun scanMenuUsesOnlyBasenamesAndFractionsForDuplicates() {
        val folders = listOf(FolderSelection("b", "Media"), FolderSelection("a", "Media"), FolderSelection("c", "Музыка"))
        val labels = scanFolderLabels(folders)
        assertEquals(mapOf("a" to "Media (1/2)", "b" to "Media (2/2)", "c" to "Музыка"), labels)
        assertEquals(labels, scanFolderLabels(folders.reversed()))
        assertEquals("Media", scanFolderLabels(folders.filter { it.uri != "a" })["b"])
        assertEquals("Media (2/3)", scanFolderLabels(folders + FolderSelection("d", "Media"))["b"])
        assertEquals("Other", scanFolderLabels(folders.map { if (it.uri == "a") it.copy(name = "Other") else it })["a"])
    }

    @Test fun currentLocationWinsOverOverlappingRoots() {
        val locations = listOf(TrackLocation("b", "content:b", "Album/song.mp3"), TrackLocation("a", "content:a", "song.mp3"))
        val track = Track("content:a", "Song", fileName = "song.mp3", relativePath = "song.mp3", locations = locations)
        assertEquals("Music/song.mp3", songLocationText(track, mapOf("a" to "Music", "b" to "Other")))
        assertEquals("song.mp3", songLocationText(track, emptyMap()))
        assertEquals("song.mp3", songLocationText(track.copy(locations = emptyList()), mapOf("b" to "Other")))
        assertEquals("content:a", track.uri)
        val reversedCandidates = locations.map { track.copy(uri = it.uri, relativePath = it.relativePath) }
        assertEquals("content:a", reversedCandidates.first { sameSongInformationLocation(it, track) }.uri)
    }
}
