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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
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
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Native artwork placement and recording regressions on real Choreographer frames. */
class AutoScrollDrawnPlacementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val covers = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val fullHost get() = arguments.getString("fullHost") == "1"
    private val albums = 300
    private var fixtureSnapshot: LibrarySnapshot? = null
    @Volatile private var lastRecorded: Map<Any, Rect> = emptyMap()
    private val originalTabs = context.getSharedPreferences("interface", 0).getString("tabs", null)
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var activity: ComponentActivity? = null
    private val drawObservers = mutableListOf<Pair<View, android.view.ViewTreeObserver.OnPreDrawListener>>()
    private var originalAnimatorScale: String? = null
    private var animatorScaleCaptured = false

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Before fun enableNativeMotion() {
        originalAnimatorScale = android.provider.Settings.Global.getString(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        check(originalAnimatorScale == null || originalAnimatorScale?.toFloatOrNull()?.let { it.isFinite() && it >= 0f } == true)
        animatorScaleCaptured = true
        // Hosted emulators disable this globally. The regression needs the real animated return.
        shell("settings put global animator_duration_scale 1")
        check(android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE) == 1f)
    }

    private val tracks by lazy {
        covers.mkdirs()
        val palette = intArrayOf(0xFFCC3322.toInt(), 0xFF2255AA.toInt(), 0xFF33AA55.toInt(), 0xFFDDAA22.toInt(), 0xFF884499.toInt())
        (0 until albums).map { n ->
            val file = File(covers, "agent99-635-$n.png").also { files += it }
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
        try {
        if (activity != null) main { drawObservers.forEach { (view, observer) -> view.viewTreeObserver.removeOnPreDrawListener(observer) }; drawObservers.clear() }
        scenario?.close()
        files.forEach { it.delete() }
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
        context.getSharedPreferences("interface", 0).edit().also { if (originalTabs == null) it.remove("tabs") else it.putString("tabs", originalTabs) }.commit()
        } finally {
            if (animatorScaleCaptured) shell(originalAnimatorScale?.let {
                "settings put global animator_duration_scale $it"
            } ?: "settings delete global animator_duration_scale")
        }
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
    private var rootScreenOffset = androidx.compose.ui.geometry.Offset.Zero
    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        var result: T? = null
        main { a ->
            fun roots(view: View): List<ViewRootForTest> = if (view is ViewRootForTest) listOf(view)
                else if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
            val composeRoots = roots(a.window.decorView)
            val rootLocation = IntArray(2)
            (composeRoots.single() as View).getLocationOnScreen(rootLocation)
            rootScreenOffset = androidx.compose.ui.geometry.Offset(rootLocation[0].toFloat(), rootLocation[1].toFloat())
            result = block(composeRoots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) })
        }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun host(route: BrowseRoute): Host {
        val style = BrowseStyle(presentation = Presentation.valueOf(arguments.getString("presentation") ?: "Standard"),
            itemsPerRow = (arguments.getString("columns") ?: "5").toInt(),
            listSize = ListSize.valueOf(arguments.getString("listSize") ?: "Normal"))
        BrowsePreferences(context).write(route, style, false)
        if (fullHost) context.getSharedPreferences("interface", 0).edit().putString("tabs", route.tab.name).commit()
        ArtworkColors.publish(tracks.associate { it.uri to (0xFF000000.toInt() or (it.uri.hashCode() and 0xFFFFFF)) })
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("fixture", "Fixture")), tracks = tracks), loading = false)
        fixtureSnapshot = library.snapshot
        val host = Host(route)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        requireNotNull(scenario).onActivity { activity = it }
        main { it.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                key(host.generation) {
                    if (fullHost) LocalicaScreen(library, io.github.anastaci1a.localica.playback.PlaybackState(),
                        onPlay = {}, onToggle = {}, onSeek = {}, onChooseFolder = {}, onRescan = {}, onCancelScan = {},
                        playlist = PlaylistState(loading = false), queues = QueueUiState(loading = false))
                    else BrowserScreen(library, PlaylistState(loading = false), host.route, { host.route = it }, {}, {}, { _, _, _ -> },
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
        val viewport = all.firstOrNull { it.tag() == "browser-list" }?.boundsInRoot?.translate(rootScreenOffset)
            ?: return@nodes emptyMap()
        val byTag = all.mapNotNull { node -> node.tag()?.let { it to node.boundsInRoot.translate(rootScreenOffset) } }.toMap()
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
        val drawnProblems = drawnBounds(label).toMutableList()
        if (arguments.getString("strict") != "0") { nativeFrame(); drawnProblems += drawnBounds("$label-next-idle") }
        if (arguments.getString("diagnostic") == "1") {
            Thread.sleep(1000)
            val later = drawnBounds("$label-later")
            android.util.Log.i("AutoScroll635Intervention", "$label later=${later.joinToString()}")
            val drawNode = overlay().second
            main { drawNode.invalidateDraw() }
            Thread.sleep(500)
            val redrawn = drawnBounds("$label-redrawn")
            android.util.Log.i("AutoScroll635Intervention", "$label redrawn=${redrawn.joinToString()}")
            scrollDeep(5)
            Thread.sleep(250)
            scrollDeep(0)
            Thread.sleep(750)
            val scrolled = drawnBounds("$label-rescrolled")
            android.util.Log.i("AutoScroll635Intervention", "$label rescrolled=${scrolled.joinToString()}")
        }
        val settled = cells()
        main { host.generation++ }
        if (arguments.getString("strict") != "0") awaitNextIdleFrame(host) else Thread.sleep(2000)
        val fresh = cells()
        return drawnProblems + settled.mapNotNull { (key, rects) ->
            val expected = fresh[key] ?: return@mapNotNull "$label $key missing after fresh settled layout"
            val worst = rects.zip(expected).maxOf { (a, b) -> maxOf(abs(a.top - b.top), abs(a.bottom - b.bottom), abs(a.left - b.left), abs(a.right - b.right)) }
            if (worst > .5f) "$label $key off by $worst: ${rects.map { it.top }} vs ${expected.map { it.top }}" else null
        }
    }

    // Reconstruct raster coverage; an antialiased edge need not equal the solid palette byte-for-byte.
    // The measured geometry tolerance stays 0.5 px.
    private fun coverPixel(pixel: Int, foreground: Int, background: Int): Boolean {
        fun channel(value: Int, shift: Int) = (value shr shift) and 255
        val br = channel(background, 16).toFloat(); val bg = channel(background, 8).toFloat(); val bb = channel(background, 0).toFloat()
        val dr = channel(foreground, 16) - br; val dg = channel(foreground, 8) - bg; val db = channel(foreground, 0) - bb
        val pr = channel(pixel, 16).toFloat(); val pg = channel(pixel, 8).toFloat(); val pb = channel(pixel, 0).toFloat()
        val alpha = ((pr - br) * dr + (pg - bg) * dg + (pb - bb) * db) / (dr * dr + dg * dg + db * db)
        return alpha >= .5f && maxOf(abs(pr - (br + alpha * dr)), abs(pg - (bg + alpha * dg)), abs(pb - (bb + alpha * db))) <= 2f
    }

    private fun drawnBounds(label: String): List<String> {
        if (arguments.getString("record") == "1") lastRecorded.forEach { (key, rect) -> android.util.Log.i("AutoScroll635Record", "$label $key recorded=$rect") }
        dumpBounds(label)
        dumpOverlay(label)
        val actual = cells()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val problems = mutableListOf<String>()
        val background = image.getPixel(0, image.height / 2)
        var measured = 0
        actual.forEach { (key, rects) ->
            val art = rects[1]
            val x = art.center.x.toInt().coerceIn(0, image.width - 1)
            val y = art.center.y.toInt().coerceIn(0, image.height - 1)
            val color = image.getPixel(x, y)
            // The fixture covers are opaque uniform colors. Ignore decode/reveal frames;
            // those have no stable color edge to measure yet.
            val palette = intArrayOf(0xFFCC3322.toInt(), 0xFF2255AA.toInt(), 0xFF33AA55.toInt(), 0xFFDDAA22.toInt(), 0xFF884499.toInt())
            if (color !in palette) {
                if (arguments.getString("strict") != "0") problems += "$label $key expected visible cover is not fully drawn: center=" + Integer.toHexString(color)
                return@forEach
            }
            measured++
            var top = y
            var bottom = y + 1
            var left = x
            var right = x + 1
            while (left > 0 && coverPixel(image.getPixel(left - 1, y), color, background)) left--
            while (right < image.width && coverPixel(image.getPixel(right, y), color, background)) right++
            while (top > 0 && coverPixel(image.getPixel(x, top - 1), color, background)) top--
            while (bottom < image.height && coverPixel(image.getPixel(x, bottom), color, background)) bottom++
            android.util.Log.i("AutoScroll635", "$label $key layout=$art drawn=$top..$bottom")
            // Pixel coverage gives integer edges; tolerate half a pixel rounding.
            val firstRow = art.top == actual.values.minOf { it[1].top }
            val measuredTop = if (firstRow) bottom - (right - left) else top
            if (abs(measuredTop - art.top) > .5f || abs(bottom - art.bottom) > .5f ||
                abs(left - art.left) > .5f || abs(right - art.right) > .5f)
                problems += "$label $key artwork drawn $top..$bottom versus $art"
        }
        val directory = requireNotNull(context.getExternalFilesDir(null))
        File(directory, label.replace(' ', '-') + "-landed.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        android.util.Log.i("AutoScroll635", "$label measured=$measured visible=${actual.size}")
        if (arguments.getString("strict") != "0" && measured != actual.size)
            problems += "$label measured $measured of "+actual.size+" expected visible artwork"
        if (measured == 0) problems += "$label no fully loaded fixture artwork measured"
        image.recycle()
        return problems
    }

    private fun field(o: Any, prefix: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == prefix || it.name.startsWith("$prefix\$") }?.let { it.isAccessible = true; return it.get(o) }
            c = c.superclass
        }
        return null
    }
    private fun method(o: Any, prefix: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredMethods.firstOrNull { (it.name == prefix || it.name.startsWith("$prefix\$")) && it.parameterCount == 0 }?.let { it.isAccessible = true; return it.invoke(o) }
            c = c.superclass
        }
        return null
    }
    private fun animationBounds(node: Any?): String {
        val sb = StringBuilder()
        var layout = node
        var depth = 0
        while (layout != null && depth < 4) {
            val chain = field(layout, "nodes") ?: break
            var n = field(chain, "head")
            while (n != null) {
                if (n.javaClass.simpleName == "BoundsAnimationModifierNode") {
                    val a = field(n, "boundsAnimation")!!
                    val anim = field(a, "animatable")
                    sb.append(" d$depth[pend=${field(a, "isPending")} run=${anim?.let { method(it, "isRunning") }} tgt=${field(a, "targetOffset")} cur=${field(a, "currentPosition")} add=${field(a, "additionalOffset")} val=${anim?.let { method(it, "getValue") }}]")
                }
                n = field(n, "child")
            }
            layout = method(layout, "getParent")
            depth++
        }
        return sb.toString()
    }
    private fun overlay(): Pair<BrowsePinchMotion, DrawModifierNode> = nodes { all ->
        var layout = field(all.first { it.tag() == "browser-list" }, "layoutNode")
        var found: Pair<BrowsePinchMotion, DrawModifierNode>? = null
        var depth = 0
        while (layout != null && depth++ < 20 && found == null) {
            var node = field(requireNotNull(field(layout, "nodes")), "head")
            while (node != null && found == null) {
                val onDraw = (field(node, "onDraw") ?: field(node, "block"))
                if (onDraw != null && node is DrawModifierNode) {
                    val motion = onDraw.javaClass.declaredFields.mapNotNull { f ->
                        f.isAccessible = true; f.get(onDraw) as? BrowsePinchMotion
                    }.firstOrNull()
                    if (motion != null) found = motion to node
                }
                node = field(node, "child")
            }
            layout = method(layout, "getParent")
        }
        requireNotNull(found) { "Retained viewport draw node not found" }
    }

    private fun dumpBounds(label: String) = nodes { all ->
        all.filter { it.tag()?.startsWith("browse-art-") == true }.forEach { node ->
            val layout = field(node, "layoutNode")
            android.util.Log.i("AutoScroll635State", "$label ${node.tag()}" + runCatching { animationBounds(layout) }.getOrElse { " error=$it" })
        }
    }

    private fun dumpOverlay(label: String) {
        val (motion, _) = overlay()
        main {
            motion.artwork.forEach { (key, part) ->
                val coordinates = part.coordinates
                val viewport = motion.viewportCoordinates
                if (coordinates?.isAttached == true && viewport?.isAttached == true) {
                    android.util.Log.i("AutoScroll635Overlay", "$label $key root=${coordinates.boundsInRoot().translate(rootScreenOffset)} local=${viewport.localBoundingBoxOf(coordinates, false)} layer=${part.layer.size}")
                }
            }
        }
    }

    private fun nativeFrame() {
        val done = java.util.concurrent.CountDownLatch(1)
        main { a -> android.view.Choreographer.getInstance().postFrameCallback {
            // Posted work runs after this frame's traversal and draw, without forcing one.
            a.window.decorView.post { done.countDown() }
        } }
        check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "No native frame" }
    }

    private var artworkIdleWitness = "not sampled"
    private fun visibleArtworkRevealsIdle(keys: Set<String>): Boolean = nodes { all ->
        var found = 0
        var pending = 0
        all.filter { node -> node.tag()?.removePrefix("browse-text-") in keys && node.tag()?.startsWith("browse-text-") == true }.forEach { node ->
            val layout = requireNotNull(field(node, "layoutNode"))
            var modifier = field(requireNotNull(field(layout, "nodes")), "head")
            var reveal: ArtworkTextReveal? = null
            while (modifier != null && reveal == null) {
                val block = field(modifier, "layerBlock")
                if (block != null) reveal = block.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                    f.isAccessible = true
                    f.get(block) as? ArtworkTextReveal
                }
                modifier = field(modifier, "child")
            }
            reveal?.let { state ->
                found++
                val animation = state.reveal
                if (animation == null || animation.isRunning || animation.value != 1f || state.waiting) pending++
            }
        }
        artworkIdleWitness = "found=$found expected=" + keys.size + " pending=$pending"
        found == keys.size && pending == 0
    }

    private fun awaitNextIdleFrame(host: Host) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (true) {
            nativeFrame()
            var idle = false
            main { a -> idle = Recomposer.runningRecomposers.value.all { !it.hasPendingWork } &&
                !a.window.decorView.isLayoutRequested && ArtworkCache.foregroundDecodes.value == 0 }
            val expected = BrowseQueries.evaluate(requireNotNull(fixtureSnapshot), emptyList(), host.route).items.associateBy { it.key }
            val visible = cells()
            val loaded = visible.isNotEmpty() && visible.keys.all { key ->
                expected[key]?.let { ArtworkCache.peek(it.coverUris)?.bitmap != null } == true
            }
            // Recomposer can be idle between animation frames. Loading reservation is a
            // layout animation and must reach its actual endpoint before native idle.
            var loadingLayoutComplete = false
            if (visible.isNotEmpty()) {
                val motion = overlay().first
                main {
                    loadingLayoutComplete = motion.itemContent.values.all { content ->
                        content.textOpacity.javaClass.declaredFields.all { f ->
                            f.isAccessible = true
                            val cell = f.get(content.textOpacity) as? BrowseLoadingCell
                            cell == null || (cell.reservation == 0f && cell.alpha == 1f)
                        }
                    }
                }
            }
            val artworkIdle = visible.isNotEmpty() && visibleArtworkRevealsIdle(visible.keys)
            if (idle && loaded && loadingLayoutComplete && artworkIdle) {
                // Assert the first completed native frame after composition/layout/decode idle.
                nativeFrame()
                android.util.Log.i("AutoScroll635Idle", "idle visible=" + visible.size + " everyCoverCached=" + loaded + " artwork=" + artworkIdleWitness)
                return
            }
            check(SystemClock.uptimeMillis() < until) { "Native composition/layout/artwork did not become idle; covers=" + loaded + " artwork=" + artworkIdleWitness }
        }
    }

    private fun observeViewportDraw(drawNode: DrawModifierNode, after: () -> Unit) {
        main { a ->
            val f = drawNode.javaClass.declaredFields.single { it.name == "onDraw" || it.name == "block" }
            f.isAccessible = true
            var wrapped: Any? = null
            val observer = android.view.ViewTreeObserver.OnPreDrawListener {
                val current = f.get(drawNode)
                if (current !== wrapped) {
                    @Suppress("UNCHECKED_CAST")
                    val original = current as (androidx.compose.ui.graphics.drawscope.ContentDrawScope) -> Unit
                    val watched: (androidx.compose.ui.graphics.drawscope.ContentDrawScope) -> Unit = { scope ->
                        original(scope)
                        after()
                    }
                    wrapped = watched
                    f.set(drawNode, watched)
                }
                true
            }
            val view = a.window.decorView
            view.viewTreeObserver.addOnPreDrawListener(observer)
            drawObservers += view to observer
        }
    }

    private fun watchViewportRecording() {
        val (motion, drawNode) = overlay()
        observeViewportDraw(drawNode) {
            val viewport = motion.viewportCoordinates
            lastRecorded = if (viewport?.isAttached != true) emptyMap() else motion.artwork.mapNotNull { (key, part) ->
                part.coordinates?.takeIf { it.isAttached }?.let { key to viewport.localBoundingBoxOf(it, false) }
            }.toMap()
        }
    }

    private fun loop(route: BrowseRoute, trigger: (Host) -> Unit) {
        val host = host(route)
        val problems = mutableListOf<String>()
        repeat((arguments.getString("rounds") ?: "3").toInt()) { i ->
            scrollDeep(200 + i * 7)
            Thread.sleep(1200)
            if (arguments.getString("record") == "1") watchViewportRecording()
            ArtworkCache.invalidate()
            if (fullHost) nodes { all -> all.first { it.tag() == "tab-option-0" }.config[SemanticsActions.OnClick].action!!.invoke() }
            else trigger(host)
            if (arguments.getString("strict") != "0") awaitNextIdleFrame(host) else Thread.sleep(3000)
            problems += check("iteration $i", host).also { it.forEach { p -> android.util.Log.i("RealProbe635", p) } }
            Thread.sleep(800)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Count the existing main viewport recorder, with no production hook or source edit. */
    @Test fun viewportDrawCounts() {
        host(BrowseRoute(MainTab.Albums))
        scrollDeep(100)
        Thread.sleep(5000)
        val drawNode = overlay().second
        val mainDraw = requireNotNull((field(drawNode, "onDraw") ?: field(drawNode, "block")))
        val draws = java.util.concurrent.atomic.AtomicInteger()
        // Recomposition replaces draw lambdas. Re-wrap the latest lambda before each native draw.
        observeViewportDraw(drawNode) { draws.incrementAndGet() }
        main { drawNode.invalidateDraw() }
        Thread.sleep(500)
        val idleStart = draws.get()
        Thread.sleep(1000)
        android.util.Log.i("AutoScroll635Counts", "idleDraws=" + (draws.get() - idleStart))
        val draw = mainDraw
        // The wrapper captures the original lambda, which owns main's LazyGridState.
        fun gridIn(lambda: Any): androidx.compose.foundation.lazy.grid.LazyGridState? {
            lambda.javaClass.declaredFields.forEach { f ->
                f.isAccessible = true
                val value = f.get(lambda)
                if (value is androidx.compose.foundation.lazy.grid.LazyGridState) return value
                if (value is kotlin.jvm.functions.Function1<*, *> && value !== lambda) gridIn(value)?.let { return it }
            }
            return null
        }
        val grid = requireNotNull(gridIn(draw)) { "Main viewport's grid not found" }
        var last = draws.get()
        var scrollFrames = 0
        var recordedFrames = 0
        val badFrames = mutableListOf<String>()
        val done = java.util.concurrent.CountDownLatch(1)
        main {
            val choreographer = android.view.Choreographer.getInstance()
            choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
                var step = 0
                override fun doFrame(time: Long) {
                    val now = draws.get()
                    if (step > 0) {
                        scrollFrames++
                        if (now > last) recordedFrames++
                        if (step > 1 && now - last != 1) badFrames += "frame=$step draws="+(now-last)
                        android.util.Log.i("AutoScroll635Counts", "scrollFrame=" + step + " draws=" + (now - last))
                    }
                    last = now
                    if (step++ < 90) {
                        check(grid.dispatchRawDelta(3f) == 3f) { "Steady scroll must consume exactly 3px" }
                        choreographer.postFrameCallback(this)
                    } else done.countDown()
                }
            })
        }
        check(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        android.util.Log.i("AutoScroll635Counts", "scrollFrames=" + scrollFrames + " recordedFrames=" + recordedFrames + " totalDraws=" + draws.get())
        assertTrue("Every steady scroll frame must record exactly once: $badFrames", badFrames.isEmpty() && recordedFrames == scrollFrames - 1)
    }

    @Test fun albumsTopRequest() = loop(BrowseRoute(MainTab.Albums)) { h -> main { h.topRequest++ } }
    @Test fun mixedTopRequest() = loop(BrowseRoute(MainTab.Library)) { h -> main { h.topRequest++ } }
    @Test fun mixedFilterToggle() = loop(BrowseRoute(MainTab.Library)) { h ->
        main { h.route = h.route.copy(filter = MediaKind.AlbumArtist) }
        Thread.sleep(250)
        main { h.route = h.route.copy(filter = null) }
    }
}
