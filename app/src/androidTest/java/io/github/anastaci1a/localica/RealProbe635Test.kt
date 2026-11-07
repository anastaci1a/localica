package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.QueueUiState
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Probe only: real Choreographer frames. */
class RealProbe635Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val covers = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()
    private val albums = 300
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var activity: ComponentActivity? = null

    private val tracks by lazy {
        covers.mkdirs()
        val palette = intArrayOf(0xFFCC3322.toInt(), 0xFF2255AA.toInt(), 0xFF33AA55.toInt(), 0xFFDDAA22.toInt(), 0xFF884499.toInt())
        (0 until albums).map { n ->
            val file = File(covers, "realprobe-635-$n.png").also { files += it }
            Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(palette[n % palette.size])
                file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val title = when (n % 4) { 0 -> "A$n"; 1 -> "Album $n with a long title"; 2 -> "B$n"; else -> "Album $n has an even longer title that wraps" }
            val artist = if (n % 5 < 2) "Artist ${n / 3}" else "Artist ${n / 3} with a long wrapping name"
            Track(android.net.Uri.fromFile(file).toString(), "Song $n", artist = artist, albumArtist = artist,
                album = title, fileName = "$n.mp3", relativePath = "a/$n.mp3")
        }
    }

    @After fun cleanup() {
        scenario?.close()
        files.forEach { it.delete() }
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
    }

    private class Host(route: BrowseRoute) {
        var route by mutableStateOf(route)
        var topRequest by mutableIntStateOf(0)
        var generation by mutableIntStateOf(0)
    }

    private fun main(block: (ComponentActivity) -> Unit) {
        val task = java.util.concurrent.FutureTask { block(requireNotNull(activity)) }
        check(android.os.Handler(android.os.Looper.getMainLooper()).post(task))
        try { task.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        catch (failure: java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
    }
    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        var result: T? = null
        main { a ->
            fun roots(view: View): List<ViewRootForTest> = if (view is ViewRootForTest) listOf(view)
                else if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
            result = block(roots(a.window.decorView).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) })
        }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun host(route: BrowseRoute): Host {
        BrowsePreferences(context).write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), false)
        ArtworkColors.publish(tracks.associate { it.uri to (0xFF000000.toInt() or (it.uri.hashCode() and 0xFFFFFF)) })
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("fixture", "Fixture")), tracks = tracks), loading = false)
        val host = Host(route)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        requireNotNull(scenario).onActivity { activity = it }
        main { it.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                key(host.generation) {
                    BrowserScreen(library, PlaylistState(loading = false), host.route, { host.route = it }, {}, {}, { _, _, _ -> },
                        null, null, true, false, queueState = QueueUiState(loading = false),
                        scrollToTopRequest = host.topRequest, reducedMotion = false)
                }
            }
        } } }
        val until = SystemClock.uptimeMillis() + 20_000
        while (nodes { all -> all.none { it.tag() == "browser-list" } }) { check(SystemClock.uptimeMillis() < until); Thread.sleep(50) }
        Thread.sleep(1500)
        return host
    }

    private fun cells(): Map<String, List<Rect>> = nodes { all ->
        val viewport = all.first { it.tag() == "browser-list" }.boundsInRoot
        val byTag = all.mapNotNull { node -> node.tag()?.let { it to node.boundsInRoot } }.toMap()
        byTag.filterKeys { it.startsWith("media-") }.mapNotNull { (tag, bounds) ->
            val key = tag.removePrefix("media-")
            if (bounds.top < viewport.top + 1f || bounds.bottom > viewport.bottom - 1f) null
            else key to listOf(bounds, byTag["browse-art-$key"] ?: Rect.Zero, byTag["browse-text-$key"] ?: Rect.Zero)
        }.toMap()
    }

    private fun scrollDeep(index: Int) = nodes { all ->
        all.first { it.tag() == "browser-list" }.config[SemanticsActions.ScrollToIndex].action!!.invoke(index)
    }

    private fun check(label: String, host: Host): List<String> {
        val settled = cells()
        main { host.generation++ }
        Thread.sleep(2000)
        val fresh = cells()
        return settled.mapNotNull { (key, rects) ->
            val expected = fresh[key] ?: return@mapNotNull null
            val worst = rects.zip(expected).maxOf { (a, b) -> maxOf(abs(a.top - b.top), abs(a.bottom - b.bottom), abs(a.left - b.left)) }
            if (worst > .5f) "$label $key off by $worst: ${rects.map { it.top }} vs ${expected.map { it.top }}" else null
        }
    }

    private fun loop(route: BrowseRoute, trigger: (Host) -> Unit) {
        val host = host(route)
        val problems = mutableListOf<String>()
        repeat(8) { i ->
            scrollDeep(200 + i * 7)
            Thread.sleep(1200)
            ArtworkCache.invalidate()
            trigger(host)
            Thread.sleep(3000)
            problems += check("iteration $i", host).also { it.forEach { p -> android.util.Log.i("RealProbe635", p) } }
            Thread.sleep(800)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun albumsTopRequest() = loop(BrowseRoute(MainTab.Albums)) { h -> main { h.topRequest++ } }
    @Test fun mixedTopRequest() = loop(BrowseRoute(MainTab.Library)) { h -> main { h.topRequest++ } }
    @Test fun mixedFilterToggle() = loop(BrowseRoute(MainTab.Library)) { h ->
        main { h.route = h.route.copy(filter = MediaKind.AlbumArtist) }
        Thread.sleep(250)
        main { h.route = h.route.copy(filter = null) }
    }
}
