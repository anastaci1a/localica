package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowsePreferences
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.browse.Presentation
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.ui.LibraryUiState
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.QueueUiState
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.ui.BrowserScreen
import io.github.anastaci1a.localica.ui.LocalicaTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * #635: scroll-driven recycling must compose every Library item at its final slot.
 *
 * Every native frame during fast flings, a run of app-scrollbar jumps (to the bottom edge too) and an
 * animated return to the top is sampled after drawing. For each visible item the approach
 * (drawn) bounds of the item, its cell, artwork and text must equal their lookahead (final) bounds,
 * and no bounds placement animation may be running. A recycled slot must not inherit the
 * previous item's animation state.
 */
class ScrollPlacementFirstFrameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val covers = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var activity: ComponentActivity? = null
    private var scope: LookaheadScope? = null
    private val palette = intArrayOf(0xFFCC3322.toInt(), 0xFF2255AA.toInt(), 0xFF33AA55.toInt(), 0xFFDDAA22.toInt(), 0xFF884499.toInt())

    /** Varied title and artist lengths make recycled cells differ in height and text layout. */
    private fun syntheticTracks(albums: Int): List<Track> {
        covers.mkdirs()
        return (0 until albums).map { n ->
            val file = File(covers, "placement-635-$n.png").also { files += it }
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(palette[n % palette.size])
                file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val title = when (n % 4) { 0 -> "A$n"; 1 -> "Album $n with a long title"; 2 -> "B$n"; else -> "Album $n has an even longer title that wraps" }
            val artist = if (n % 5 < 2) "Artist ${n / 3}" else "Artist ${n / 3} with a long wrapping name"
            Track(android.net.Uri.fromFile(file).toString(), "Song $n", artist = artist,
                albumArtist = artist, album = title, fileName = "$n.mp3", relativePath = "a/$n.mp3")
        }
    }

    /** Optional operator-scale fixture: a pushed corpus directory with tracks.jsonl and its tagged files. */
    private fun corpusTracks(directory: File): List<Track> = File(directory, "tracks.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
        val json = org.json.JSONObject(line)
        // Artwork comes from each file's embedded cover, as in a scanned library.
        val file = File(directory, json.getString("relative_path"))
        Track(android.net.Uri.fromFile(file).toString(), json.getString("title"),
            artist = json.getString("artist"), albumArtist = json.getString("artist"), album = json.getString("album"),
            fileName = json.getString("relative_path").substringAfterLast('/'), relativePath = json.getString("relative_path"))
    }

    @After fun cleanup() {
        scenario?.close()
        files.forEach { it.delete() }
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
    }

    private class Host { var topRequest by mutableIntStateOf(0) }

    private fun main(block: (ComponentActivity) -> Unit) {
        val task = java.util.concurrent.FutureTask { block(requireNotNull(activity)) }
        check(android.os.Handler(android.os.Looper.getMainLooper()).post(task))
        try { task.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        catch (failure: java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
    }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    private fun semantics(a: ComponentActivity): List<SemanticsNode> {
        fun roots(view: View): List<ViewRootForTest> = if (view is ViewRootForTest) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
        return roots(a.window.decorView).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
    }

    private fun host(style: BrowseStyle): Host {
        val route = BrowseRoute(MainTab.Albums)
        BrowsePreferences(context).write(route, style, false)
        val tracks = arguments.getString("corpus")?.let { corpusTracks(File(it)) }
            ?: syntheticTracks((arguments.getString("albums") ?: "1500").toInt())
        ArtworkColors.publish(tracks.associate { it.uri to palette[(it.uri.hashCode() and 0x7fffffff) % palette.size] })
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("fixture", "Fixture")), tracks = tracks), loading = false)
        val host = Host()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        requireNotNull(scenario).onActivity { activity = it }
        main { it.setContent { LocalicaTheme {
            // Any scope instance converts coordinates to their lookahead counterparts.
            LookaheadScope { SideEffect { scope = this } }
            Box(Modifier.fillMaxSize()) {
                BrowserScreen(library, PlaylistState(loading = false), route, { }, {}, {}, { _, _, _ -> },
                    null, null, true, false, queueState = QueueUiState(loading = false),
                    scrollToTopRequest = host.topRequest, reducedMotion = false)
            }
        } } }
        val until = SystemClock.uptimeMillis() + 30_000
        while (true) {
            var ready = false
            main { a -> ready = semantics(a).any { it.tag() == "browser-list" } && semantics(a).any { it.tag()?.startsWith("browse-art-") == true } }
            if (ready) break
            check(SystemClock.uptimeMillis() < until) { "Browser grid did not compose" }
            Thread.sleep(50)
        }
        awaitIdle()
        return host
    }

    private fun awaitIdle() {
        val until = SystemClock.uptimeMillis() + 30_000
        var quiet = 0
        while (quiet < 10) {
            nativeFrame()
            var idle = false
            main { a -> idle = Recomposer.runningRecomposers.value.all { !it.hasPendingWork } && !a.window.decorView.isLayoutRequested }
            quiet = if (idle) quiet + 1 else 0
            check(SystemClock.uptimeMillis() < until) { "Browser did not become idle" }
        }
    }

    private fun nativeFrame() {
        val done = java.util.concurrent.CountDownLatch(1)
        main { a -> Choreographer.getInstance().postFrameCallback { a.window.decorView.post { done.countDown() } } }
        check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "No native frame" }
    }

    // Reflection over Compose node chains: production code stays free of test hooks.
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name || it.name.startsWith("$name\$") }?.let { it.isAccessible = true; return it.get(o) }
            c = c.superclass
        }
        return null
    }
    private fun call(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredMethods.firstOrNull { (it.name == name || it.name.startsWith("$name\$")) && it.parameterCount == 0 }?.let { it.isAccessible = true; return it.invoke(o) }
            c = c.superclass
        }
        return null
    }

    /** Running or pending bounds animations on one layout node, described for the failure log. */
    private fun activeAnimations(layout: LayoutInfo): List<String> {
        val active = mutableListOf<String>()
        var node = field(field(layout, "nodes") ?: return active, "head")
        while (node != null) {
            // Library animateBounds node: idle means not pending and not running.
            if (node.javaClass.simpleName == "BoundsAnimationModifierNode") {
                val animation = field(node, "boundsAnimation")
                if (animation != null && call(animation, "isIdle") == false) active += "animateBounds(pending=" + field(animation, "isPending") +
                    " from=" + field(animation, "currentPosition") + "/" + field(animation, "currentSize") +
                    " to=" + field(animation, "targetOffset") + "/" + field(animation, "targetSize") + ")"
            }
            // Any project bounds-motion node reports through the same name.
            if (node.javaClass.declaredMethods.any { it.name == "isPlacementAnimating" } && call(node, "isPlacementAnimating") == true)
                active += node.javaClass.simpleName
            node = field(node, "child")
        }
        return active
    }

    private class Sample(val frame: Int, val problems: List<String>, val keys: Set<String>)

    private var frame = 0
    private val firstSeen = mutableMapOf<String, Int>()
    private val slotOwner = mutableMapOf<Int, String>()
    private val reusedFrom = mutableMapOf<String, String>()
    private val violations = mutableListOf<String>()
    private var checkedItems = 0
    private var enteredItems = 0

    /** Compare approach and lookahead geometry for every placed item intersecting the viewport. */
    private fun sample(a: ComponentActivity, phase: String) {
        val lookahead = requireNotNull(scope)
        val all = semantics(a)
        val grid = all.firstOrNull { it.tag() == "browser-list" } ?: return
        val gridInfo = grid.layoutInfo
        val gridCoordinates = gridInfo.coordinates
        if (!gridCoordinates.isAttached) return
        val height = gridCoordinates.size.height
        val byTag = all.mapNotNull { node -> node.tag()?.let { it to node.layoutInfo } }.toMap()
        frame++
        val visible = mutableSetOf<String>()
        byTag.keys.filter { it.startsWith("media-") }.forEach { tag ->
            val key = tag.removePrefix("media-")
            val cell = byTag.getValue(tag)
            var root: LayoutInfo = cell
            val chain = mutableListOf<LayoutInfo>()
            while (root.parentInfo != null && root.parentInfo !== gridInfo) { chain += root; root = requireNotNull(root.parentInfo) }
            if (root.parentInfo !== gridInfo || !root.isPlaced || !root.coordinates.isAttached) return@forEach
            val top = gridCoordinates.localPositionOf(root.coordinates, Offset.Zero).y
            if (top + root.coordinates.size.height <= 0f || top >= height) return@forEach
            visible += key
            checkedItems++
            val slot = System.identityHashCode(root)
            val entered = key !in lastVisible
            if (entered) {
                enteredItems++
                firstSeen[key] = frame
                slotOwner[slot]?.takeIf { it != key }?.let { reusedFrom[key] = it } ?: reusedFrom.remove(key)
            }
            slotOwner[slot] = key
            val age = frame - (firstSeen[key] ?: frame)
            val parts = listOf("item" to root, "cell" to cell) +
                listOf("art", "text", "info", "menu").mapNotNull { part -> byTag["browse-$part-$key"]?.let { part to it } }
            val problems = mutableListOf<String>()
            parts.forEach { (name, info) ->
                val c = info.coordinates
                if (!c.isAttached) return@forEach
                val approach = gridCoordinates.localPositionOf(c, Offset.Zero)
                val target = with(lookahead) { gridCoordinates.localLookaheadPositionOf(c) }
                val targetSize = with(lookahead) { c.toLookaheadCoordinates().size }
                val worst = maxOf(abs(approach.x - target.x), abs(approach.y - target.y),
                    abs((c.size.width - targetSize.width).toFloat()), abs((c.size.height - targetSize.height).toFloat()))
                if (worst > .5f) problems += "$name drawn at (${approach.x},${approach.y}) ${c.size} but slot is (${target.x},${target.y}) $targetSize"
            }
            (chain + root + listOf("art", "text", "info", "menu").mapNotNull { byTag["browse-$it-$key"] }).distinct().forEach { info ->
                activeAnimations(info).forEach { problems += "placement animation active: $it" }
            }
            if (problems.isNotEmpty()) violations += "[$phase] frame $frame item $key (frame ${age} since it entered" +
                (reusedFrom[key]?.let { ", slot recycled from $it" } ?: "") + "): " + problems.joinToString("; ")
        }
        lastVisible = visible
    }
    private var lastVisible: Set<String> = emptySet()

    /** Sample after every native draw until stopped. */
    private fun startSampler(phase: () -> String): () -> Unit {
        var running = true
        main { a ->
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (!running) return
                    a.window.decorView.post { if (running) sample(a, phase()) }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        return { main { running = false } }
    }

    private fun swipe(fromY: Float, toY: Float, x: Float, durationMs: Long = 48, steps: Int = 6) {
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long, y: Float) = MotionEvent.obtain(start, time, action, x, y, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.sendPointerSync(event(MotionEvent.ACTION_DOWN, start, fromY))
        for (i in 1..steps) {
            val time = start + durationMs * i / steps
            while (SystemClock.uptimeMillis() < time) Thread.sleep(1)
            instrumentation.sendPointerSync(event(MotionEvent.ACTION_MOVE, time, fromY + (toY - fromY) * i / steps))
        }
        instrumentation.sendPointerSync(event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), toY))
    }

    private fun gridBounds(): android.graphics.RectF {
        var bounds = android.graphics.RectF()
        main { a ->
            val grid = semantics(a).first { it.tag() == "browser-list" }
            val location = IntArray(2)
            a.window.decorView.getLocationOnScreen(location)
            val r = grid.boundsInWindow
            bounds = android.graphics.RectF(r.left + location[0], r.top + location[1], r.right + location[0], r.bottom + location[1])
        }
        return bounds
    }

    /** The app scrollbar's own request path: fraction to item target to a cold jump. */
    private fun scrollbar(fraction: Float) = main { a ->
        check(semantics(a).first { it.tag() == "browse-scrollbar" }.config[SemanticsActions.SetProgress].action!!.invoke(fraction))
    }


    private fun run(style: BrowseStyle, label: String) {
        val host = host(style)
        var phase = "fling"
        val stop = startSampler { phase }
        try {
            val bounds = gridBounds()
            val x = bounds.left + bounds.width() * .4f
            // Fast flings down the catalog, chained before each settles.
            repeat(3) {
                swipe(bounds.top + bounds.height() * .85f, bounds.top + bounds.height() * .15f, x)
                Thread.sleep(120)
            }
            Thread.sleep(2500)
            // A fling back up, then scrollbar jumps: one cold jump per frame, including the end.
            phase = "fling-back"
            swipe(bounds.top + bounds.height() * .15f, bounds.top + bounds.height() * .85f, x)
            Thread.sleep(1500)
            phase = "scrollbar-jump"
            val steps = 40
            for (i in 0..steps) {
                scrollbar(((i * 7) % (steps + 1)).toFloat() / steps)
                nativeFrame()
            }
            // A drag along the last fifth of the track ends at the bottom edge.
            for (i in 0..20) {
                scrollbar(.8f + .01f * i)
                nativeFrame()
            }
            Thread.sleep(1500)
            // The programmatic return to the top (Library tab re-tap) from deep in the catalog.
            phase = "autoscroll-top"
            main { host.topRequest++ }
            Thread.sleep(3000)
            awaitIdle()
        } finally { stop() }
        android.util.Log.i("Placement635", "$label frames=$frame checked=$checkedItems entered=$enteredItems violations=${violations.size}")
        violations.take(200).forEach { android.util.Log.i("Placement635", "$label $it") }
        assertTrue("$label: $enteredItems item entries over $frame frames; ${violations.size} item samples were away from their slots " +
            "or had a running placement animation:\n" + violations.take(12).joinToString("\n"), violations.isEmpty() && enteredItems > 0)
    }

    @Test fun listFlingAndJump() = run(BrowseStyle(presentation = Presentation.List, listSize = ListSize.Normal), "list")
    @Test fun gallery3FlingAndJump() = run(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3), "gallery3")
    @Test fun gallery4FlingAndJump() = run(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 4), "gallery4")
    @Test fun gallery5FlingAndJump() = run(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), "gallery5")
}
