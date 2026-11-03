package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** A scrolled-in row's text waits for its artwork and fades in with the same reveal value (#526). */
class ArtworkTextRevealTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()

    @After fun cleanUp() { files.forEach { it.delete() }; ArtworkCache.invalidate(); io.github.anastaci1a.localica.library.ArtworkColors.publish(emptyMap()) }

    private fun cover(index: Int): String {
        directory.mkdirs()
        val file = File(directory, "text-reveal-526-$index.png")
        file.outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        files += file
        return android.net.Uri.fromFile(file).toString()
    }

    @Test fun textAlphaTracksArtworkAlphaThroughAScrollReveal() {
        ArtworkCache.invalidate()
        val items = (0 until 30).map { BrowserItem(MediaRef(MediaKind.Playlist, "p$it"), "Reveal title $it", null, emptyList(), coverUri = cover(it)) }
        // Stored colours mark these covers as existing artwork, which is what gates text.
        io.github.anastaci1a.localica.library.ArtworkColors.publish(items.associate { it.coverUris.single() to 0xFFCC2020.toInt() })
        val style = BrowseStyle(listSize = ListSize.Normal, extraInfo = ExtraInfo.None)
        val geometry = BrowseCellGeometry(1)
        val list = mutableStateOf<androidx.compose.foundation.lazy.LazyListState?>(null)
        compose.setContent { LocalicaTheme {
            // Loading material hidden: art pixels read as alpha over white, text ink as alpha over white.
            CompositionLocalProvider(LocalLoadingMaterialOpacity provides 0f, LocalContentColor provides Color.Black) {
                val state = rememberLazyListState().also { list.value = it }
                LookaheadScope {
                    LazyColumn(Modifier.size(360.dp, 400.dp).background(Color.White), state) {
                        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                            BrowseItemLayout(item, style, geometry, this@LookaheadScope, false, {}, itemIndex = index)
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val target = items[22]
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { runBlocking { requireNotNull(list.value).scrollToItem(22) } }
        compose.mainClock.advanceTimeByFrame()
        fun artAlpha() = compose.onNodeWithTag("browse-art-${target.key}", true).captureToImage().toPixelMap()
            .let { 1f - it[it.width / 2, it.height / 2].green }
        fun ink() = compose.onNodeWithTag("browse-text-${target.key}", true).captureToImage().toPixelMap().let { map ->
            var sum = 0f
            for (y in 0 until map.height) for (x in 0 until map.width) map[x, y].let { sum += 1f - (it.red + it.green + it.blue) / 3f }
            sum
        }
        assertEquals("Art is still loading on the first scrolled frame", 0f, artAlpha(), .05f)
        assertEquals("Text waits for the art", 0f, ink(), 1f)
        // Let the IO decode finish in real time while frames stay paused, then step frame by frame.
        Thread.sleep(800)
        val samples = mutableListOf<Pair<Float, Float>>()
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            samples += artAlpha() to ink()
        }
        compose.mainClock.advanceTimeBy(600)
        val finalInk = ink()
        assertTrue("Text is shown after the reveal: $finalInk", finalInk > 5f)
        assertEquals(1f, artAlpha(), .03f)
        val pairs = samples.map { (art, ink) -> art to ink / finalInk }
        val revealing = pairs.filter { it.first in .08f..0.92f }
        assertTrue("Several intermediate reveal frames were sampled: $pairs", revealing.size >= 3)
        revealing.forEach { (art, text) -> assertTrue("Text alpha $text tracks art alpha $art in $pairs", abs(art - text) < .15f) }
    }

    @Test fun cachedArtworkShowsTextImmediately() {
        val uri = cover(99)
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "cached"), "Cached title", null, emptyList(), coverUri = uri)
        val retention = BrowseArtworkScope()
        retention.hold("seed", listOf(uri), ArtworkCache.CachedArtwork(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }, 768))
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseArtwork provides retention, LocalContentColor provides Color.Black) {
                LookaheadScope {
                    Box(Modifier.size(360.dp, 120.dp).background(Color.White)) {
                        BrowseItemLayout(item, BrowseStyle(listSize = ListSize.Normal, extraInfo = ExtraInfo.None), BrowseCellGeometry(1), this@LookaheadScope, false, {})
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        val map = compose.onNodeWithTag("browse-text-${item.key}", true).captureToImage().toPixelMap()
        var ink = 0f
        for (y in 0 until map.height) for (x in 0 until map.width) map[x, y].let { ink += 1f - (it.red + it.green + it.blue) / 3f }
        assertTrue("Cached art: text on the first frame ($ink)", ink > 5f)
    }

    /** Art not known to exist (unindexed, or a failing read) never hides the title. */
    @Test fun unindexedLoadingArtworkShowsTextImmediately() {
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "unindexed"), "Unindexed title", null, emptyList(), coverUri = "content://test.unindexed/never-526")
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                LookaheadScope {
                    Box(Modifier.size(360.dp, 120.dp).background(Color.White)) {
                        BrowseItemLayout(item, BrowseStyle(listSize = ListSize.Normal, extraInfo = ExtraInfo.None), BrowseCellGeometry(1), this@LookaheadScope, false, {})
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        val map = compose.onNodeWithTag("browse-text-${item.key}", true).captureToImage().toPixelMap()
        var ink = 0f
        for (y in 0 until map.height) for (x in 0 until map.width) map[x, y].let { ink += 1f - (it.red + it.green + it.blue) / 3f }
        assertTrue("Unindexed art: text on the first frame ($ink)", ink > 5f)
    }
}
