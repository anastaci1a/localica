package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The actual shared row action follows the artwork clock, including unindexed covers. */
class RowMenuRevealTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    private var clicks = 0

    @After fun cleanUp() {
        files.forEach { it.delete() }
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
    }

    private fun cover(): String {
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val file = File.createTempFile("menu-588-", ".png", directory)
        file.outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        files += file
        return android.net.Uri.fromFile(file).toString()
    }

    private fun show(item: BrowserItem, retention: BrowseArtworkScope? = null, size: ListSize = ListSize.Normal,
        loading: BrowseLoadingCell? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseArtwork provides retention, LocalLoadingMaterialOpacity provides 0f,
                LocalContentColor provides Color.Black) {
                LookaheadScope {
                    Box(Modifier.size(360.dp, 160.dp).background(Color.White)) {
                        BrowseItemLayout(item, BrowseStyle(listSize = size, extraInfo = ExtraInfo.None),
                            BrowseCellGeometry(1), this@LookaheadScope, false,
                            menu = { drawn -> if (drawn) ActionIcon(AppIcon.ActionEllipsis, "Reveal actions", quiet = true) { clicks++ } },
                            loadingReveal = loading)
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun indexedArtworkAndMenuUseTheSameRevealFrames() = checkLoad(true)
    @Test fun unindexedArtworkStillHidesTheLoadingMenu() = checkLoad(false)

    private fun checkLoad(indexed: Boolean) {
        ArtworkCache.invalidate()
        val uri = cover()
        if (indexed) ArtworkColors.publish(mapOf(uri to 0xFFCC2020.toInt()))
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-load"), "Menu title", null, emptyList(), coverUri = uri)
        show(item)
        fun artAlpha(): Float {
            val pixels = compose.onNodeWithTag("browse-art-${item.key}", true).captureToImage().toPixelMap()
            return 1f - pixels[pixels.width / 2, pixels.height / 2].green
        }
        fun menuInk() = ink(compose.onNodeWithTag("browse-menu-${item.key}", true).captureToImage())
        val output = nativeEvidenceDirectory("row-menu-reveal", context.getExternalFilesDir(null))
        File(output, "indexed-$indexed-loading.png").outputStream().use {
            compose.onNodeWithTag("browse-menu-${item.key}", true).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals("Artwork starts at its loading state", 0f, artAlpha(), .05f)
        assertEquals("The loading menu has no painted ink", 0f, menuInk(), 1f)
        compose.onNodeWithContentDescription("Reveal actions").assertDoesNotExist()
        compose.onNodeWithTag("browse-menu-${item.key}", true).performTouchInput { click() }
        assertEquals("Invisible menu cannot activate", 0, clicks)
        compose.waitUntil(5000) { ArtworkCache.peek(listOf(uri))?.bitmap != null }
        val samples = mutableListOf<Pair<Float, Float>>()
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            samples += artAlpha() to menuInk()
        }
        compose.mainClock.advanceTimeBy(500)
        val full = menuInk()
        File(output, "indexed-$indexed-frames.txt").writeText("full-menu-ink=$full\n" +
            samples.joinToString("\n") { (art, menu) -> "art=$art menu=${menu / full}" })
        assertTrue("Actual ellipsis has visible ink", full > 5f)
        val intermediate = samples.map { it.first to it.second / full }.filter { it.first in .08f..0.92f }
        assertTrue("Sample several artwork reveal frames: $samples", intermediate.size >= 3)
        intermediate.forEach { (art, menu) -> assertTrue("Menu alpha $menu matches artwork $art", abs(art - menu) < .12f) }
        compose.onNodeWithContentDescription("Reveal actions").performClick()
        assertEquals(1, clicks)
    }

    @Test fun cachedArtworkShowsItsMenuOnTheFirstFrame() {
        val uri = cover()
        val retention = BrowseArtworkScope()
        retention.hold("seed", listOf(uri), ArtworkCache.CachedArtwork(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }, 768))
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-cached"), "Cached", null, emptyList(), coverUri = uri)
        show(item, retention)
        assertReadyMenuAcceptsTouch(item)
    }

    @Test fun rowsWithoutArtworkShowTheirMenuImmediately() {
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-no-art"), "No artwork", null, emptyList())
        show(item, size = ListSize.Small)
        assertReadyMenuAcceptsTouch(item)
    }

    @Test fun emptyArtworkSlotShowsItsMenuOnTheFirstFrame() {
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-empty"), "Empty", null, emptyList())
        show(item)
        assertReadyMenuAcceptsTouch(item)
    }

    @Test fun cachedMissingArtworkShowsItsMenuOnTheFirstFrame() {
        val uri = cover()
        val retention = BrowseArtworkScope()
        retention.hold("missing", listOf(uri), ArtworkCache.CachedArtwork(null, 768))
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-cached-missing"), "Missing", null, emptyList(), coverUri = uri)
        show(item, retention)
        assertReadyMenuAcceptsTouch(item)
    }

    @Test fun indexedMissingArtworkShowsItsMenuOnTheFirstFrame() {
        val uri = cover()
        ArtworkColors.publish(mapOf(uri to ArtworkColors.MISSING))
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-indexed-missing"), "Missing", null, emptyList(), coverUri = uri)
        show(item)
        assertReadyMenuAcceptsTouch(item)
    }

    @Test fun failedArtworkReadReleasesTheMenuWithoutAnImageFade() = checkFailedArtwork(deleted = true)

    @Test fun undecodableArtworkReleasesTheMenuWithoutAnImageFade() = checkFailedArtwork(deleted = false)

    private fun checkFailedArtwork(deleted: Boolean) {
        val uri = cover()
        val file = File(android.net.Uri.parse(uri).path!!)
        if (deleted) assertTrue(file.delete()) else file.writeText("not an image")
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-failed"), "Failed", null, emptyList(), coverUri = uri)
        show(item)
        compose.waitUntil(5000) { ArtworkCache.peek(listOf(uri)) != null }
        assertNull("The real reader completed with no image", ArtworkCache.peek(listOf(uri))!!.bitmap)
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("Reveal actions").fetchSemanticsNodes().isNotEmpty()
        }
        val menu = compose.onNodeWithTag("browse-menu-${item.key}", true)
        val firstInk = ink(menu.captureToImage())
        assertReadyMenuAcceptsTouch(item)
        compose.mainClock.advanceTimeBy(320)
        assertEquals("Failed artwork opens the menu at full opacity, without an image fade", firstInk, ink(menu.captureToImage()), 1f)
    }

    private fun assertReadyMenuAcceptsTouch(item: BrowserItem) {
        assertTrue(ink(compose.onNodeWithTag("browse-menu-${item.key}", true).captureToImage()) > 5f)
        val action = compose.onNodeWithContentDescription("Reveal actions").assertIsDisplayed().assertHasClickAction()
        compose.awaitExpectedWindowFocus(action)
        action.performTouchInput { click() }
        assertEquals("Visible menu accepts real touch input", 1, clicks)
    }

    @Test fun rowsWithoutArtworkWaitForTheirContentRevealBeforeAcceptingInput() {
        val reveal = BrowseLoadingReveal(1, false)
        val cell = reveal.cell(0).also { it.place(0f, 0f) }
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "menu-content"), "Loading content", null, emptyList())
        show(item, size = ListSize.Small, loading = cell)
        compose.onNodeWithContentDescription("Reveal actions").assertDoesNotExist()
        compose.onNodeWithTag("browse-menu-${item.key}", true).performTouchInput { click() }
        assertEquals(0, clicks)
        compose.runOnIdle { reveal.finish() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Reveal actions").performClick()
        assertEquals(1, clicks)
    }

    private fun ink(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var total = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width)
            total += 1f - (pixels[x, y].red + pixels[x, y].green + pixels[x, y].blue) / 3f
        return total
    }
}
