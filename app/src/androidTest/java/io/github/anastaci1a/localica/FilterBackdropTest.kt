package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilterBackdropTest {
    @get:Rule val compose = createComposeRule()
    @Test fun libraryFiltersRefractTheFadedScene() = inspect(false)
    @Test fun searchFiltersRefractTheFadedScene() = inspect(true)

    private fun inspect(search: Boolean) {
        val route = BrowseRoute(if (search) MainTab.Home else MainTab.Library,
            search = search, globalSearch = search, text = if (search) "Song" else "", filter = MediaKind.Song)
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = prefs.read(route)
        prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 1), false)
        val tracks = (0..20).map { Track("filter:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(512 * 512) { i ->
                if ((i % 512 / 28 + i / 512 / 28) % 2 == 0) android.graphics.Color.RED else android.graphics.Color.BLUE
            }, 0, 512, 0, 0, 512, 512)
        }
        val art = BrowseArtworkScope().apply {
            tracks.forEach { hold(it.id, listOf(it.uri), ArtworkCache.CachedArtwork(bitmap, 2048)) }
        }
        var depth by mutableFloatStateOf(.7f)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalBrowseArtwork provides art,
                    LocalGlassAppearance provides GlassAppearance(depth = depth, blur = 0f, whiteTint = 0f,
                        contrast = 0f, shadowStrength = 0f)) {
                    Box(Modifier.size(320.dp, 500.dp)) {
                        BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                            PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = true)
                    }
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("browser-list").performScrollToIndex(3)
            // End Search autofocus and let the older Android snapshot refresh settle.
            if (search) pressSystemBack()
            compose.mainClock.advanceTimeBy(1200)
            // Exercise a ready lens independently of the separate first-recording race.
            compose.runOnIdle { depth = .6f }
            val filter = compose.onNodeWithTag("filter-Songs")
            val capture = filter.captureToImage()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.cacheDir, "filter-${if (search) "search" else "library"}.png").outputStream().use {
                capture.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val before = capture.toPixelMap()
            var green = 0f; var samples = 0
            for (y in before.height * 2 / 3 until before.height * 4 / 5) for (x in before.width / 4 until before.width * 3 / 4) {
                green += before[x, y].green; samples++
            }
            assertTrue("The lens must include the white fade, not reveal raw red/blue art: ${green / samples}", green / samples > .3f)
            compose.runOnIdle { depth = 2f }
            val after = filter.captureToImage().toPixelMap()
            var refracted = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                val a = before[x, y]; val b = after[x, y]
                // Color differences exclude black lettering and neutral illumination.
                if (kotlin.math.abs((a.red - a.blue) - (b.red - b.blue)) > .02f) refracted++
            }
            assertTrue("Filter optics must move colored source pixels, changed=$refracted", refracted > 30)
        } finally { prefs.write(route, previous, false) }
    }
}
