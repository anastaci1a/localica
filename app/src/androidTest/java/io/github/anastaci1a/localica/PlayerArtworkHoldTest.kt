package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #575: holding the full-screen player's artwork force-closed v0.32.0. Real app tree, real long-press. */
class PlayerArtworkHoldTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var track: Track
    private lateinit var playback: PlaybackState

    /** A real decoded cover, as on the operator's phone: the player and its popups sample artwork pixels. */
    private fun coverTrack(context: android.content.Context): Track {
        val directory = java.io.File(context.filesDir, "playlist-covers").apply { mkdirs() }
        val file = java.io.File(directory, "hold-575.png")
        val bitmap = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(230, 200, 60))
            drawCircle(300f, 300f, 200f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(30, 60, 160) })
        }
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Track(android.net.Uri.fromFile(file).toString(), "Morning light", artist = "Localica", fileName = file.name, relativePath = file.name)
    }

    @Test fun holdOpensArtworkMenuOverFullBackgroundArtwork() = hold(ArtworkLayout.FullBackground, lyrics = false)
    @Test fun holdOpensArtworkMenuOverFloatingArtwork() = hold(ArtworkLayout.Floating, lyrics = false)
    @Test fun holdWithLyricsOpenOverFullBackgroundArtworkDoesNotCrash() = hold(ArtworkLayout.FullBackground, lyrics = true)
    @Test fun holdWithLyricsOpenOverFloatingArtworkDoesNotCrash() = hold(ArtworkLayout.Floating, lyrics = true)

    private fun hold(layout: ArtworkLayout, lyrics: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        track = coverTrack(context)
        playback = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title,
            artist = track.artist, durationMs = 125000)
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(artworkLayout = layout))
        val queue = QueueEdits.create(listOf(track), 0, "Morning")
        compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
            playback, {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
            playlist = PlaylistState(loading = false)) } }
        compose.onNodeWithTag("player-surface").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Show lyrics").fetchSemanticsNodes().isNotEmpty() }
        if (lyrics) {
            compose.onNodeWithContentDescription("Show lyrics").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics-panel", true).fetchSemanticsNodes().isNotEmpty() }
        }
        // The cover decodes off the main thread; let it land and the blend settle before the hold.
        Thread.sleep(1500)
        compose.waitForIdle()
        val art = compose.onNodeWithTag("artwork-gesture", true).fetchSemanticsNode().boundsInRoot
        val title = compose.onAllNodesWithTag("player-lyrics-title-target", true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val density = context.resources.displayMetrics.density
        // The cover's own centre, kept above the title target that overlaps a full-background cover.
        val point = Offset(art.center.x,
            if (title != null && title.top > art.top) minOf(art.center.y, title.top - 16 * density) else art.center.y)
        assertTrue("$layout hold point $point lies on the artwork $art", art.contains(point))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { longClick(point - root.topLeft) }
        compose.waitForIdle()
        if (!lyrics) {
            compose.waitUntil(5000) { compose.onAllNodesWithText("Configure tap action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("View artwork").assertIsDisplayed()
            compose.onNodeWithText("Configure tap action").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Configure tap action").fetchSemanticsNodes().isEmpty() }
        } else {
            // Lyrics own the cover gesture while open: a hold must neither crash nor open the artwork menu.
            compose.onNodeWithText("Configure tap action").assertDoesNotExist()
        }
        compose.onNodeWithTag("player-surface").assertExists()
    }
}
