package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtistAlbumSelectionTest {
    @get:Rule val compose = createComposeRule()
    private fun albums(names: List<String>) = LibraryViews.groups(LibrarySnapshot(tracks = names.mapIndexed { i, name ->
        Track("album-choice:$i", name, album = name, artist = "Artist", albumArtist = "Artist", fileName = "$i", relativePath = "$i")
    }), SongListView.Albums, LibraryQuery())

    @Test fun allShortAndLongPressModesPreserveOtherAlbumsAndEmitOneRecognitionHaptic() {
        val albums = albums(listOf("First", "Second", "Third"))
        val ids = albums.associate { it.title to it.id }
        var explicit by mutableStateOf(emptyList<String>())
        val changes = mutableListOf<List<String>>()
        var longPressTimeout = 0L
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
            SideEffect { longPressTimeout = timeout }
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                ArtistAlbumChoices(albums, explicit) { explicit = it; changes += it }
            }
        } }
        fun album(name: String) = compose.onNodeWithText(name).performScrollTo()
        fun all() {
            compose.onNodeWithText("All albums").performScrollTo().assertIsSelected()
            for (name in ids.keys) album(name).assertIsSelected().assertIsOn()
        }
        fun only(vararg names: String) {
            compose.onNodeWithText("All albums").performScrollTo().assertIsNotSelected()
            for (name in ids.keys) {
                if (name in names) album(name).assertIsSelected().assertIsOn()
                else album(name).assertIsNotSelected().assertIsOff()
            }
        }
        fun hold(name: String) {
            val node = album(name)
            val before = changes.size
            val haptics = pulses.size
            val autoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            var held = false
            try {
                // Keep the native contact down while Compose's recognition timer runs.
                // Batched event timestamps alone can dispatch UP before that timer fires.
                node.performTouchInput { down(center); held = true }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(longPressTimeout + 1)
                compose.runOnIdle {
                    assertEquals("$name recognizes before release", before + 1, changes.size)
                    assertEquals("$name emits one recognition pulse while held",
                        listOf(ControlHaptic.Strong), pulses.drop(haptics))
                }
                node.performTouchInput { up(); held = false }
                compose.runOnIdle {
                    assertEquals("$name has no trailing short tap", before + 1, changes.size)
                    assertEquals("$name has no release pulse", listOf(ControlHaptic.Strong), pulses.drop(haptics))
                }
            } finally {
                try {
                    if (held) node.performTouchInput { cancel() }
                } finally {
                    compose.mainClock.autoAdvance = autoAdvance
                }
            }
        }
        all()
        album("First").performClick(); only("First")
        album("First").performClick(); only("First")
        compose.runOnIdle { assertEquals(listOf(ids.getValue("First")), explicit); assertTrue(pulses.isEmpty()) }
        hold("Second"); only("First", "Second")
        hold("Third"); all()
        compose.runOnIdle { assertTrue("Full explicit set returns to canonical All", explicit.isEmpty()) }
        album("First").performClick(); only("First")
        hold("Second"); only("First", "Second")
        hold("First"); only("Second")
        hold("Second"); all()
        hold("First"); only("Second", "Third")
        album("Second").performClick(); only("Second")
        compose.runOnIdle { assertEquals(listOf(ids.getValue("Second")), explicit) }
    }

    @Test fun singleAlbumHasAllAndExplicitSingleStatesWithoutEverEmptyingItsSongScope() {
        val albums = albums(listOf("Only album"))
        var explicit by mutableStateOf(emptyList<String>())
        var changes = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                ArtistAlbumChoices(albums, explicit) { explicit = it; changes++ }
            }
        } }
        val only = compose.onNodeWithText("Only album")
        compose.onNodeWithText("All albums").assertIsSelected()
        only.assertIsSelected().assertIsOn().performClick()
        compose.onNodeWithText("All albums").assertIsNotSelected()
        only.assertIsSelected().assertIsOn().performClick()
        compose.onNodeWithText("All albums").assertIsNotSelected()
        val before = changes
        only.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(before + 1, changes)
            assertEquals(listOf(ControlHaptic.Strong), pulses)
            assertTrue(explicit.isEmpty())
        }
        compose.onNodeWithText("All albums").assertIsSelected()
        only.assertIsSelected().assertIsOn()
    }
}
