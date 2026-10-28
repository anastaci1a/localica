package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
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
        var host: android.view.View? = null
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { LocalicaTheme {
            val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
            val view = LocalView.current
            SideEffect { longPressTimeout = timeout; host = view }
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it }) {
                ArtistAlbumChoices(albums, explicit) { explicit = it; changes += it }
            }
        } }
        fun album(name: String): SemanticsNodeInteraction {
            // Position the LazyRow item itself before injecting a physical contact.
            // A retained offscreen descendant can survive performScrollTo without
            // becoming a displayed hit target in this isolated carousel fixture.
            val index = albums.indexOfFirst { it.title == name }
            check(index >= 0)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index + 1)
            return compose.onNodeWithText(name).assertIsDisplayed()
        }
        fun allChoice(): SemanticsNodeInteraction {
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            return compose.onNodeWithText("All albums").assertIsDisplayed()
        }
        fun all() {
            allChoice().assertIsSelected()
            for (name in ids.keys) album(name).assertIsSelected().assertIsOn()
        }
        fun only(vararg names: String) {
            allChoice().assertIsNotSelected()
            for (name in ids.keys) {
                if (name in names) album(name).assertIsSelected().assertIsOn()
                else album(name).assertIsNotSelected().assertIsOff()
            }
        }
        fun hold(name: String) {
            val node = album(name).assertIsDisplayed()
            val semantics = node.fetchSemanticsNode()
            val coordinates = semantics.layoutInfo.coordinates
            var downLocal: Offset? = null
            fun trace(phase: String) {
                compose.runOnUiThread {
                    val attached = coordinates.isAttached
                    val local = downLocal
                    val location = IntArray(2)
                    host?.getLocationOnScreen(location)
                    val clipped = if (attached) semantics.boundsInRoot else null
                    val first = if (attached) coordinates.localToRoot(Offset.Zero) else null
                    val last = if (attached) coordinates.localToRoot(Offset(
                        coordinates.size.width.toFloat(), coordinates.size.height.toFloat())) else null
                    Log.i("AlbumHoldDiagnostic", "name=$name phase=$phase uptime=${SystemClock.uptimeMillis()} " +
                        "virtual=${compose.mainClock.currentTime} timeout=$longPressTimeout " +
                        "attached=$attached viewAttached=${host?.isAttachedToWindow} windowFocused=${host?.hasWindowFocus()} " +
                        "hostScreen=${location.toList()} clipped=$clipped layoutFirst=$first layoutLast=$last " +
                        "layoutSize=${coordinates.size} downLocal=$local " +
                        "clippedOriginPlusDown=${if (clipped != null && local != null) clipped.topLeft + local else null} " +
                        "layoutMappedDown=${if (attached && local != null) coordinates.localToRoot(local) else null} " +
                        "changes=${changes.size} pulses=$pulses selected=$explicit")
                }
            }
            trace("before-freeze")
            val before = changes.size
            val haptics = pulses.size
            val autoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            var held = false
            try {
                // Keep the native contact down while Compose's recognition timer runs.
                // Batched event timestamps alone can dispatch UP before that timer fires.
                node.performTouchInput { downLocal = center; down(center); held = true }
                trace("down-dispatched")
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(longPressTimeout + 1)
                trace("recognition-deadline")
                compose.runOnIdle {
                    assertEquals("$name recognizes before release", before + 1, changes.size)
                    assertEquals("$name emits one recognition pulse while held",
                        listOf(ControlHaptic.Strong), pulses.drop(haptics))
                }
                node.performTouchInput { up(); held = false }
                trace("up-dispatched")
                compose.runOnIdle {
                    assertEquals("$name has no trailing short tap", before + 1, changes.size)
                    assertEquals("$name has no release pulse", listOf(ControlHaptic.Strong), pulses.drop(haptics))
                }
            } catch (failure: Throwable) {
                try {
                    trace("failure")
                    Log.i("AlbumHoldDiagnostic", "name=$name semantics=" + compose.onRoot().printToString(maxDepth = 12))
                } catch (diagnostic: Throwable) {
                    if (diagnostic !== failure) failure.addSuppressed(diagnostic)
                }
                throw failure
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
