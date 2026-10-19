package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Sample actual decoded covers, not artwork bounds or missing-art placeholders. */
class PinchArtworkContinuityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun galleryArtworkStaysVisibleOnEveryOutwardPinchAndReleaseFrame() = pinch(true, false)
    @Test fun galleryArtworkStaysVisibleOnEveryInwardPinchAndReleaseFrame() = pinch(false, false)
    @Test fun galleryArtworkStaysVisibleThroughReversalAndCancellation() = pinch(true, true)

    @Test fun newlyExposedArtworkDrawsWhileSourceCaptureIsPending() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
        }
        val motion = BrowsePinchMotion().apply { capturing = true; source = BrowseStyle() }
        lateinit var view: View
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            Box(Modifier.size(100.dp).testTag("new-artwork")) {
                Box(Modifier.size(100.dp).retainPinchItem("new-row", motion)) {
                    ArtworkImage(bitmap.asImageBitmap(), false, Modifier.size(100.dp))
                }
                PresentationMarker { 1 }
            }
        } }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("new-artwork"), 1) { view }
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("A new row is outside the frozen atlas but must still draw: $center", center.green > .9f && center.red < .1f)
    }

    private fun pinch(outward: Boolean, cancelled: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val cover = File.createTempFile("pinch-frame-", ".png", directory)
        val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(16, 240, 48))
        }
        try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val uri = Uri.fromFile(cover).toString()
        // Eliminate I/O timing and bucket changes as explanations for a missing frame.
        runBlocking { for (edge in listOf(128, 384, 768)) assertNotNull(ArtworkCache.read(context, uri, edge)) }
        val prefs = BrowsePreferences(context)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "artwork-continuity"))
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = if (outward) 3 else 2)
        val tracks = (0..39).map { Track(uri, "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "art-frame:$it") }
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        val playlists = PlaylistState()
        val queue = ListeningQueue(id = "artwork-continuity", name = "Artwork frames",
            entries = tracks.map { QueueEntry(id = it.id, track = it) })
        val queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        var pointersDown = false
        prefs.write(route, initial, overridden)
        try {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                Box(Modifier.size(360.dp, 480.dp).testTag("art-frame-scene")) {
                    BrowserScreen(library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                        queueState = queues, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                    PresentationMarker { stage }
                }
            } }
            val scene = compose.onNodeWithTag("art-frame-scene")
            val art = compose.onNodeWithTag("browse-art-art-frame:0", true)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-art-art-frame:0", true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val list = compose.onNodeWithTag("browser-list")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val x = viewport.width / 2f
            val y = art.fetchSemanticsNode().boundsInRoot.center.y - viewport.top
            fun sample(label: String) {
                compose.runOnIdle { stage = (stage + 1) % 24 }
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                val center = art.fetchSemanticsNode().boundsInRoot.center - scene.fetchSemanticsNode().boundsInRoot.topLeft
                val cx = center.x.toInt(); val cy = center.y.toInt()
                val green = (-2..2).sumOf { dy -> (-2..2).count { dx ->
                    val c = pixels[(cx + dx).coerceIn(0, pixels.width - 1), (cy + dy).coerceIn(0, pixels.height - 1)]
                    c.green > .8f && c.red < .2f && c.blue < .3f
                } }
                if (green < 23) {
                    val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                        ?: requireNotNull(context.getExternalFilesDir(null))
                    val output = File(root, "pinch-retained-highlight").also { it.mkdirs() }
                    val failure = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
                    try {
                        failure.setPixels(IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() },
                            0, pixels.width, 0, 0, pixels.width, pixels.height)
                        File(output, "artwork-$outward-$cancelled-$label.png").outputStream().use { failure.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { failure.recycle() }
                }
                assertTrue("Decoded artwork must remain on every presented frame: $label center=$center green=$green/25", green >= 23)
            }
            sample("initial")
            compose.mainClock.autoAdvance = false
            pointersDown = true
            list.performTouchInput {
                down(0, Offset(x - 60f, y)); down(1, Offset(x + 60f, y))
                val span = if (outward) 64f else 56f
                moveTo(0, Offset(x - span, y)); moveTo(1, Offset(x + span, y))
            }
            var recognitionFrame = 0
            val targetDescription = if (outward) "Gallery, 2 columns" else "Gallery, 3 columns"
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                sample("recognition-${recognitionFrame++}")
                list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == targetDescription
            }
            list.performTouchInput {
                val span = if (outward) 96f else 39f
                moveTo(0, Offset(x - span, y)); moveTo(1, Offset(x + span, y))
            }
            repeat(24) { compose.mainClock.advanceTimeByFrame(); sample("ease-$it") }
            if (cancelled) {
                list.performTouchInput { moveTo(0, Offset(x - 60f, y)); moveTo(1, Offset(x + 60f, y)) }
                repeat(24) { compose.mainClock.advanceTimeByFrame(); sample("reverse-$it") }
            }
            list.performTouchInput { if (cancelled) cancel() else { up(0); up(1) } }
            pointersDown = false
            repeat(24) { compose.mainClock.advanceTimeByFrame(); sample("release-$it") }
            assertEquals(if (cancelled) initial.itemsPerRow else if (outward) 2 else 3, prefs.read(route).itemsPerRow)
        } finally {
            try { if (pointersDown) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
            finally {
                compose.mainClock.autoAdvance = true
                prefs.write(route, original, overridden)
                cover.delete()
            }
        }
    }
}
