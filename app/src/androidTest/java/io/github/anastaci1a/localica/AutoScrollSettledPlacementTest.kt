package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.QueueUiState
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * #635: after a long programmatic scroll back to the top, during which artwork was evicted and
 * reloads, every visible item must settle exactly in its slot without waiting for user input.
 */
class AutoScrollSettledPlacementTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val covers = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()
    private val albums = 240

    private val tracks by lazy {
        covers.mkdirs()
        val palette = intArrayOf(0xFFCC3322.toInt(), 0xFF2255AA.toInt(), 0xFF33AA55.toInt(), 0xFFDDAA22.toInt(), 0xFF884499.toInt())
        (0 until albums).map { n ->
            val file = File(covers, "autoscroll-635-$n.png").also { files += it }
            Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(palette[n % palette.size])
                file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            // Titles of one, two and three lines vary the gallery row heights.
            val title = when (n % 3) { 0 -> "A$n"; 1 -> "Album $n with a long title"; else -> "Album $n has an even longer title that wraps" }
            Track(android.net.Uri.fromFile(file).toString(), "Song $n", artist = "Artist ${n % 7}",
                album = title, fileName = "$n.mp3", relativePath = "a/$n.mp3")
        }
    }

    @Before @After fun reset() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
    }
    @After fun deleteCovers() { files.forEach { it.delete() } }

    private class Host(route: BrowseRoute) {
        var route by mutableStateOf(route)
        var topRequest by mutableIntStateOf(0)
        var generation by mutableIntStateOf(0)
    }

    private fun host(route: BrowseRoute, style: BrowseStyle): Host {
        BrowsePreferences(context).write(route, style, false)
        // A stored colour marks every cover as expected art, like an indexed library.
        ArtworkColors.publish(tracks.associate { it.uri to (0xFF000000.toInt() or (it.uri.hashCode() and 0xFFFFFF)) })
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("fixture", "Fixture")), tracks = tracks), loading = false)
        val host = Host(route)
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize().testTag("autoscroll-host")) {
                key(host.generation) {
                    BrowserScreen(library, PlaylistState(loading = false), host.route, { host.route = it }, {}, {}, { _, _, _ -> },
                        null, null, true, false, queueState = QueueUiState(loading = false),
                        scrollToTopRequest = host.topRequest, reducedMotion = false)
                }
            }
        } }
        return host
    }

    private val list get() = compose.onNodeWithTag("browser-list")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    /** Every visible cell's outer, artwork and text bounds, by item key. */
    private fun cells(): Map<String, List<Rect>> {
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val byTag = nodes.mapNotNull { node -> node.tag()?.let { it to node.boundsInRoot } }.toMap()
        return byTag.filterKeys { it.startsWith("media-") }.mapNotNull { (tag, bounds) ->
            val key = tag.removePrefix("media-")
            if (bounds.top < viewport.top + 1f || bounds.bottom > viewport.bottom - 1f) null
            else key to listOf(bounds, byTag["browse-art-$key"] ?: Rect.Zero, byTag["browse-text-$key"] ?: Rect.Zero)
        }.toMap()
    }

    private fun settle() {
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        // Longer than every placement tween and the reveal/stagger windows.
        repeat(6) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
    }

    private fun assertSettledInSlots(label: String, host: Host) {
        settle()
        val settled = cells()
        assertTrue("$label: visible cells", settled.size >= 5)
        // Reference: a fresh composition of the same top viewport places every slot directly.
        compose.runOnIdle { host.generation++ }
        settle()
        val fresh = cells()
        val problems = settled.mapNotNull { (key, rects) ->
            val expected = fresh[key] ?: return@mapNotNull "$key missing after relayout"
            val worst = rects.zip(expected).maxOf { (a, b) ->
                maxOf(abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom))
            }
            android.util.Log.i("Autoscroll635", "$label $key worst=$worst settled=${rects.map { it.top }} fresh=${expected.map { it.top }}")
            if (worst > .5f) "$key off by $worst px: ${rects.map { it.top }} vs ${expected.map { it.top }}" else null
        }
        assertTrue("$label: items must settle in their slots without input:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun farThenTop(columns: Int, route: BrowseRoute = BrowseRoute(MainTab.Albums), presentation: Presentation = Presentation.Standard,
        listSize: ListSize = ListSize.Normal, trigger: (Host) -> Unit = { compose.runOnIdle { it.topRequest++ } }) {
        val host = host(route, BrowseStyle(presentation = presentation, itemsPerRow = columns, listSize = listSize))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        settle()
        list.performScrollToIndex(albums - 10)
        settle()
        assertTrue(position() > 0f)
        // Evict every decoded cover so the return reloads the top viewport from storage.
        ArtworkCache.invalidate()
        compose.mainClock.autoAdvance = false
        trigger(host)
        // Step frames against the real clock so cover reads complete mid-motion, as on a device.
        repeat(240) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); Thread.sleep(6) }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(15_000) { compose.mainClock.advanceTimeByFrame(); position() == 0f }
        assertSettledInSlots("$presentation/$listSize/$columns columns", host)
    }

    @Test fun fiveColumnGalleryTabReturn() = farThenTop(5)
    @Test fun threeColumnGalleryTabReturn() = farThenTop(3)
    @Test fun twoColumnGalleryTabReturn() = farThenTop(2)
    @Test fun normalListTabReturn() = farThenTop(1, presentation = Presentation.List)
    @Test fun largeListTabReturn() = farThenTop(1, presentation = Presentation.List, listSize = ListSize.Large)

    private fun filterSwitch(host: Host) {
        compose.runOnIdle { host.route = host.route.copy(filter = MediaKind.AlbumArtist) }
        repeat(30) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); Thread.sleep(6) }
        compose.runOnIdle { host.route = host.route.copy(filter = MediaKind.Album) }
    }
    @Test fun fiveColumnGalleryFilterSwitch() = farThenTop(5, BrowseRoute(MainTab.Library, filter = MediaKind.Album), trigger = ::filterSwitch)
    @Test fun threeColumnGalleryFilterSwitch() = farThenTop(3, BrowseRoute(MainTab.Library, filter = MediaKind.Album), trigger = ::filterSwitch)
}
