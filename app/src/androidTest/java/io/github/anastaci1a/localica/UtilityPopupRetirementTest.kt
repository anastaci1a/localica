package io.github.anastaci1a.localica

import android.view.Window
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #667: every close of the ⋯ utility popup retires its window and flight, and restores the pill, for the whole session. */
class UtilityPopupRetirementTest {
    @get:Rule val compose = localicaComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences = InterfacePreferences(instrumentation.targetContext)
    private val previous = preferences.read()
    private lateinit var home: GlassPopupHome
    private var popup: Window? = null
    private var progress = 0f

    @After fun restorePreferences() = preferences.write(previous)

    @Test fun dismissRetiresTheOverflowAndRestoresThePill() {
        host()
        openOverflow()
        UiDevice.getInstance(instrumentation).pressBack()
        assertRetired()
        assertPillAndNoPopupContent()
        roundTrip()
    }

    @Test fun settingsThenBackRetiresTheOverflow() {
        host()
        openOverflow()
        compose.onNodeWithText("Settings").performClick()
        assertRetired()
        advance(120)
        UiDevice.getInstance(instrumentation).pressBack()
        advance(240)
        assertPillAndNoPopupContent()
        assertTrue("The utility flight is gone after Settings returns", flights().isEmpty())
        roundTrip()
    }

    @Test fun scanThenDismissRetiresTheOverflow() {
        host()
        openOverflow()
        compose.onNodeWithText("Scan music").performClick()
        assertRetired()
        advance(120)
        UiDevice.getInstance(instrumentation).pressBack()
        advance(240)
        assertPillAndNoPopupContent()
        assertTrue("The utility flight is gone after the scanner returns", flights().isEmpty())
        roundTrip()
    }

    private fun host() {
        preferences.write(InterfaceSettings(reducedMotion = false, fasterMotion = false))
        val track = Track("file:///missing-667.mp3", "One", artist = "Test", album = "One",
            fileName = "missing-667.mp3", relativePath = "missing-667.mp3")
        compose.setContent { LocalicaTheme {
            home = requireNotNull(LocalGlassPopupHome.current)
            CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, p ->
                if (kind == "dialog" && window != null && popup == null) popup = window
                if (kind == "geometry" && window === popup && p != null) progress = p
            }) {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                    PlaybackState(connected = true, trackId = track.id, trackUri = track.id,
                        title = track.title, artist = track.artist, durationMs = 125000),
                    {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
            }
        } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun openOverflow() {
        popup = null
        progress = 0f
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(10_000) {
            advance(1)
            popup?.decorView?.isAttachedToWindow == true && progress >= .999f &&
                compose.onAllNodesWithText("Scan music").fetchSemanticsNodes().size == 1
        }
        advance(60)
        compose.waitUntil(5000) { requireNotNull(popup).decorView.hasWindowFocus() }
    }

    private fun advance(frames: Int) = repeat(frames) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    /** The collapse ran to its end and the native window left within a bounded number of frames. */
    private fun assertRetired() {
        val window = requireNotNull(popup)
        var lowest = 1f
        var frames = 0
        while (window.decorView.isAttachedToWindow && frames++ < 240) {
            advance(1)
            lowest = minOf(lowest, progress)
        }
        assertFalse("The overflow's native window retires after the close", window.decorView.isAttachedToWindow)
        assertTrue("The collapse ran (lowest phase $lowest)", lowest < .2f)
        assertTrue("The utility flight is removed from its home", flights().isEmpty())
    }

    private fun flights(): List<GlassPopupFlight> = compose.runOnUiThread {
        fun collect(h: GlassPopupHome): List<GlassPopupFlight> = h.flights.toList() + h.children.flatMap(::collect)
        collect(home)
    }

    private fun assertPillAndNoPopupContent() {
        assertTrue("No Scan music row is composed", compose.onAllNodesWithText("Scan music").fetchSemanticsNodes().isEmpty())
        assertTrue("No Settings row is composed", compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("More").assertExists()
        compose.onNodeWithContentDescription("Search library").assertExists()
    }

    /** The stuck popup rode along every tab for the rest of the session. */
    private fun roundTrip() {
        compose.onNodeWithContentDescription("Playlists").performClick()
        advance(60)
        compose.onNodeWithContentDescription("Library").performClick()
        advance(60)
        assertTrue(flights().isEmpty())
        assertPillAndNoPopupContent()
    }
}
