package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScopedSearchOpticsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun playlistSearchRefractsItsRecordedBackdrop() = inspect(false)
    @Test fun queueSearchRefractsItsRecordedBackdrop() = inspect(true)

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    fun searchLensRedrawsWhenItsListBackdropArrivesLater() {
        var loaded by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = .4f,
                contrast = 0f, shadowStrength = 0f)) {
                val source = rememberFrostedBackdrop(Unit, enabled = false)
                Box(Modifier.size(320.dp, 100.dp)) {
                    if (loaded) Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Red) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        ListSearchPill("Delayed list", {}, Modifier.padding(12.dp).testTag("delayed-pill"))
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { loaded = true }
        val image = compose.onNodeWithTag("delayed-pill").captureToImage().toPixelMap()
        // Tint is emitted only by a rendered lens, not by the red background or
        // the white foreground (which is confined to the left).
        var edgePixels = 0
        for (y in image.height / 3..image.height * 2 / 3) for (x in image.width * 3 / 4 until image.width * 4 / 5)
            if (image[x, y].red > .9f && image[x, y].green > .1f && image[x, y].green < .6f) edgePixels++
        assertTrue("Late list recording must wake its stationary search lens", edgePixels > 10)
    }

    private fun inspect(queue: Boolean) {
        val tracks = (0..20).map { Track("search:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val artwork = BrowseArtworkScope()
        val coverUri = "test:scoped-optics-" + java.util.UUID.randomUUID()
        if (!queue) {
            // A playlist now owns its opaque collection background. Supply the
            // pattern through that real cover/wash path, not behind the whole page.
            val bitmap = android.graphics.Bitmap.createBitmap(256, 640, android.graphics.Bitmap.Config.ARGB_8888)
            val pixels = IntArray(bitmap.width * bitmap.height) { index ->
                if (((index % bitmap.width) / 10 + (index / bitmap.width) / 10) % 2 == 0)
                    android.graphics.Color.RED else android.graphics.Color.BLUE
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            artwork.keepVisual(coverUri, PlayerVisual(bitmap.asImageBitmap(),
                PlayerPalette(Color.Black, Color.Black, Color.Black, wash = bitmap, collectionWash = bitmap)))
        }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Optics", coverUri = coverUri.takeUnless { queue }), tracks)
        val listening = ListeningQueue(name = "Optics", entries = tracks.map { QueueEntry(track = it) })
        val route = BrowseRoute(if (queue) MainTab.Queues else MainTab.Playlists,
            MediaRef(if (queue) MediaKind.Queue else MediaKind.Playlist, if (queue) listening.id else playlist.id), "Optics")
        var depth by mutableFloatStateOf(.6f)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(depth = depth, blur = 0f,
                whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                val backing = rememberFrostedBackdrop(Unit)
                Box(Modifier.size(320.dp, 500.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) {
                        val cell = 10.dp.toPx()
                        for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt())
                            drawRect(if ((x + y) % 2 == 0) Color.Red else Color.Blue,
                                Offset(x * cell, y * cell), Size(cell, cell))
                    }
                    CompositionLocalProvider(LocalFrostedBackdrop provides backing, LocalBrowseArtwork provides artwork) {
                        BrowserScreen(LibraryUiState(loading = false), PlaylistState(listOf(playlist), false),
                            route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                            queueState = QueueUiState(QueueLibrary(listOf(listening), listening.id), loading = false),
                            reducedMotion = true)
                    }
                }
            }
        } }
        val pill = compose.onNodeWithTag("list-search-pill")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-pill").fetchSemanticsNodes().isNotEmpty() }
        if (!queue) pill.performScrollTo()
        pill.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        val before = pill.captureToImage().toPixelMap()
        val reds = (before.height / 3 until before.height * 2 / 3).flatMap { y ->
            (before.width / 3 until before.width * 2 / 3).map { x -> before[x, y].red }
        }
        assertTrue("The actual source must present a spatial pattern before comparing refraction",
            reds.isNotEmpty() && reds.maxOrNull()!! - reds.minOrNull()!! > .15f)
        compose.runOnIdle { depth = 2f }
        val image = pill.captureToImage()
        val after = image.toPixelMap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "scoped-search-${if (queue) "queue" else "playlist"}.png").outputStream().use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width)
            if (kotlin.math.abs(before[x, y].red - after[x, y].red) > .15f) changed++
        assertTrue("Scoped search needs spatial refraction, changed=$changed", changed > before.width * before.height / 20)
    }
}
