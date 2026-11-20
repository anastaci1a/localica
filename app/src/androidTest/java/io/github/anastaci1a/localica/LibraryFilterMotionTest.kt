package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class LibraryFilterMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordedFilterStripKeepsTheBottomShadowProfile() {
        lateinit var recording: FrostedBackdrop
        lateinit var coordinates: LayoutCoordinates
        var showBubble by mutableStateOf(true)
        var reference by mutableStateOf<Rect?>(null)
        var selected = false
        compose.setContent { EngineTheme(reduced = true) {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            SideEffect { recording = source }
            Box(Modifier.size(240.dp, 208.dp).onGloballyPositioned { coordinates = it }
                .testTag("recorded-filter").recordFrostedBackdrop(source).background(Color.White)) {
                if (showBubble) {
                    Box(Modifier.offset(y = 72.dp)) {
                        OrderedGlassBubbles(listOf("Albums"), emptySet(), { it }, true, onSelect = { selected = true })
                    }
                }
                val density = LocalDensity.current
                reference?.let { bounds ->
                    Box(Modifier.glassShadowOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                        .clearGlassShadow(1f, 1f, null))
                }
            }
        } }
        val bubble = compose.onNodeWithTag("Albums").assertIsDisplayed()
        val body = bubble.fetchSemanticsNode().layoutInfo.coordinates
        val density = compose.density.density
        val bounds = compose.runOnUiThread {
            assertTrue("The actual strip must be drawn through a recorded scene", recording.recorded && recording.recordings > 0)
            val origin = coordinates.localPositionOf(body, Offset.Zero)
            assertEquals("The visual gutter does not add horizontal input padding", (12 * density).roundToInt(), origin.x.roundToInt())
            val height = (40 * density).roundToInt()
            val top = origin.y + ((body.size.height - height) / 2f).roundToInt()
            Rect(origin.x, top, origin.x + body.size.width, top + height)
        }
        bubble.performClick()
        compose.runOnIdle { assertTrue("The recorded bubble still accepts input", selected) }
        val columns = (bounds.center.x - 6 * density).toInt()..(bounds.center.x + 6 * density).toInt()
        val rows = (bounds.bottom + 16 * density).toInt()..(bounds.bottom + 45 * density).toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = nativeEvidenceDirectory("recorded-filter-shadow", context.cacheDir)
        fun capture(savePill: Boolean = false): List<Color> {
            val frame = compose.onNodeWithTag("recorded-filter").captureToImage()
            if (savePill) java.io.File(output, "filter-pill.png").outputStream().use {
                check(frame.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            val image = frame.toPixelMap()
            return rows.flatMap { row -> columns.map { image[it, row] } }
        }
        val actual = capture(savePill = true)
        compose.runOnIdle { showBubble = false }
        val off = capture()
        compose.runOnIdle { reference = bounds }
        val uncut = capture()
        val maximum = actual.indices.maxOf { pixel ->
            val a = actual[pixel]
            val b = uncut[pixel]
            maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha)) * 255f
        }
        val recordedDrop = actual.indices.maxOf { (off[it].red - actual[it].red) * 255 }
        val referenceDrop = uncut.indices.maxOf { (off[it].red - uncut[it].red) * 255 }
        java.io.File(output, "bottom-profile.txt").writeText(
            "bounds=$bounds rows=$rows cols=$columns recordedDrop=$recordedDrop referenceDrop=$referenceDrop parity=$maximum\n" +
            rows.zip(actual.chunked(columns.count()).zip(uncut.chunked(columns.count()))).joinToString("\n") { (row, bands) ->
                "$row ${bands.first.map { it.red }.average()} ${bands.second.map { it.red }.average()}"
            } + "\n")
        assertTrue("Both arms must show a bottom tail, recorded=$recordedDrop reference=$referenceDrop", recordedDrop > 1f && referenceDrop > 1f)
        assertTrue("The recorded bottom tail agrees with bypassing the strip to one code, maximum=$maximum", maximum <= 1.00001f)
    }

    @Test fun selectionLeadsAndDeselectingRestoresConfiguredOrderThroughInterruptions() {
        val filters = listOf(LibraryFilter.Artists, LibraryFilter.Songs, LibraryFilter.Albums)
        var selected by mutableStateOf<MediaKind?>(null)
        compose.setContent { LocalicaTheme { EngineRecord {
            LibraryFilterBubbles(filters, selected, false, { selected = it }, {})
        } } }
        fun left(name: String) = compose.onNodeWithTag("filter-$name").fetchSemanticsNode().boundsInRoot.left
        val original = listOf(left("Artists"), left("Songs"), left("Albums"))
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Albums").assertIsSelected()
        assertTrue(left("Albums") < left("Artists"))
        compose.onNodeWithText("Albums").performClick()
        assertEquals(null, selected)
        assertEquals(original[0], left("Artists"), 1f)
        assertEquals(original[1], left("Songs"), 1f)
        assertEquals(original[2], left("Albums"), 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = MediaKind.Album }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { selected = MediaKind.Song }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { selected = null }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(original[0], left("Artists"), 1f)
        assertEquals(original[1], left("Songs"), 1f)
        assertEquals(original[2], left("Albums"), 1f)
    }

    @Test fun filtersAndSearchHaveEqualBodiesAndPlacementAtLargeTextSizes() {
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Column {
                    Box(Modifier.fillMaxWidth().testTag("filters-slot")) {
                        EngineRecord { LibraryFilterBubbles(listOf(LibraryFilter.Albums), null, true, {}, {}) }
                    }
                    Box(Modifier.fillMaxWidth().testTag("search-slot")) {
                        ListSearchPill("Search Albums", {}, Modifier.padding(horizontal = 12.dp, vertical = ListPillVerticalInset))
                    }
                }
            }
        } }
        val filterSlot = compose.onNodeWithTag("filters-slot").fetchSemanticsNode().boundsInRoot
        val searchSlot = compose.onNodeWithTag("search-slot").fetchSemanticsNode().boundsInRoot
        val filter = compose.onNodeWithTag("filter-Albums").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
        assertEquals(filterSlot.height, searchSlot.height, 1f)
        assertEquals(filter.height, search.height, 1f)
        assertEquals(filter.top - filterSlot.top, search.top - searchSlot.top, 1f)
    }

    @Test fun legacyAllIsRemovedWithoutChangingOrderOrCombinedContents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LibraryFilterPreferences(context)
        val old = preferences.read()
        val store = context.getSharedPreferences("library-filters", 0)
        try {
            store.edit().putString("visible", "Albums,All,Songs")
                .putStringSet("all", setOf("Artist", "Folder")).commit()
            assertEquals(listOf(LibraryFilter.Albums, LibraryFilter.Songs), preferences.read().visible)
            assertEquals(setOf(MediaKind.Artist, MediaKind.Folder), preferences.read().allCollections)
            store.edit().putString("visible", "All").commit()
            assertTrue(preferences.read().visible.isEmpty())
            preferences.write(preferences.read())
            assertTrue(preferences.read().visible.isEmpty())
        } finally { preferences.write(old) }
    }
}
