package io.github.anastaci1a.localica

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as PixelColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor

/** Three actual caller surfaces on the same real Library screen: utility More, the
 * direct playlist plus menu, and Sort/View. This is descriptive evidence, not band acceptance
 * or a matched-crop causal test. One original native Window copy per caller; each real caller
 * keeps its own natural geometry, window, and source registration. All originals are retained
 * before reading pixels. No image-driven waiting, screenshot retry, or production alteration. */
class PopupFamilyEdgeComparisonTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var host: GlassDialogs
    private lateinit var output: File
    private val records = JSONArray()
    private var requests = 0
    // Main-thread, non-snapshot diagnostic storage. Only scalar receipts from this route's
    // measured native root/window are retained; old routes are never used to fill a missing draw.
    private data class SurfaceDraw(val sequence: Long, val frame: GlassSurfaceDrawObservation)
    private val surfaceDraws = ArrayDeque<SurfaceDraw>()
    private val surfaceDrawLimit = 256
    private var observedRootIdentity: Int? = null
    private var observedWindowIdentity: Int? = null
    private var surfaceDrawSequence = 0L
    private var previousFactsSequence = 0L
    private var surfaceDrawsDropped = 0
    private var collectionStartedUptime = 0L
    private val labels = listOf("library-utility", "playlist-plus", "sort-view")
    private val tracks = (1..3).map { index ->
        Track("popup-family:$index", "Diagnostic song $index", artist = "Diagnostic artist", album = "Diagnostic album",
            fileName = "diagnostic-$index.mp3", relativePath = "Diagnostic album/diagnostic-$index.mp3",
            locations = listOf(TrackLocation("popup-family-root", "popup-family:$index", "Diagnostic album/diagnostic-$index.mp3")))
    }

    @Test fun realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance() {
        val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null))
        output = File(base, "popup-family-edge-comparison/api${Build.VERSION.SDK_INT}-${SystemClock.uptimeMillis()}")
        assertTrue(output.mkdirs())
        writeManifest("starting")
        // Preserve preferences exactly, including absence of migration flags; no production data store is seeded.
        val preferences = listOf("interface", "browse-style", "library-query", "library-filters", "pins", "queue-settings")
            .associate { name -> context.getSharedPreferences(name, 0).let { it to it.all.toMap() } }
        val playlistDb = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        var mounted by mutableStateOf(true)
        var contentInstalled = false
        var primary: Throwable? = null
        try {
            preferences.keys.forEach { assertTrue(it.edit().clear().commit()) }
            InterfacePreferences(context).write(InterfaceSettings())
            val app = context.applicationContext as LocalicaApplication
            val editor = app.tagEditing.state.value
            assertTrue("Do not alter an existing editor/recovery/job presentation", editor.tracks.isEmpty() &&
                !editor.busy && !editor.jobsVisible && editor.recovery == null)
            lateinit var playlists: PlaylistController
            lateinit var queues: QueueController
            compose.runOnUiThread {
                playlists = PlaylistController(playlistDb.playlists(), scope)
                queues = QueueController(queueDb.queues(), QueuePreferences(context), app.library, scope)
            }
            runBlocking { withTimeout(5000) {
                playlists.state.first { !it.loading }; queues.state.first { !it.loading }
            } }
            val catalog = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("popup-family-root", "Diagnostic music")), tracks), loading = false)
            compose.setContent {
                val inherited = LocalGlassSurfaceDrawObserver.current
                val observe = remember(inherited) { { frame: GlassSurfaceDrawObservation ->
                    // Preserve an existing observer without letting its failure suppress this collector.
                    try { inherited?.invoke(frame) } catch (_: Throwable) { }
                    collectSurfaceDraw(frame)
                } }
                CompositionLocalProvider(LocalGlassSurfaceDrawObserver provides observe) {
                if (mounted) LocalicaTheme {
                    val ordinaryHost = requireNotNull(LocalGlassDialogs.current)
                    SideEffect { host = ordinaryHost }
                    val playlistState by playlists.state.collectAsState()
                    val queueState by queues.state.collectAsState()
                    // Same provider chain as MainActivity, including real Edit tags menu availability.
                    TagEditorHost(app.tagEditing) {
                        LocalicaScreen(catalog, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                            queues = queueState, queueController = queues,
                            playlist = playlistState, playlistController = playlists)
                    }
                }
                }
            }
            contentInstalled = true
            compose.onNodeWithContentDescription("Library").performClick()
            compose.onNode(hasTestTag("page-heading") and hasText("Library")).assertIsDisplayed()

            capture("library-utility", "Library root > utility More", hasText("Scan music"), retainedDialog = false) {
                compose.onNodeWithContentDescription("More", substring = false).performClick()
            }
            dismiss()
            capture("playlist-plus", "Library root > Add playlist", hasText("Create playlist"), retainedDialog = false) {
                compose.onNodeWithContentDescription("Add playlist").performClick()
            }
            dismiss()
            capture("sort-view", "Library root > Sort/View (Sort tab)", hasTestTag("browse-options-dialog"), retainedDialog = true) {
                compose.onNodeWithContentDescription("Sort", substring = false).performClick()
            }
            dismiss()

            assertEquals("One request for each declared surface; no replacements", labels.size, requests)
            assertEquals(labels.size, records.length())
            // Only after every original has been retained, derive descriptive profiles.
            writeEdgeProfiles()
            writeManifest("diagnostic-recorded-NOT-band-acceptance")
        } catch (failure: Throwable) {
            primary = failure
            runCatching { writeManifest("failed", failure.toString()) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            // Attempt every cleanup; never replace the first diagnostic failure with teardown noise.
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary!!.addSuppressed(failure)
            } }
            val bodyFailed = primary != null
            clean { compose.mainClock.autoAdvance = true }
            if (contentInstalled) clean { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
            clean { runBlocking { job.cancelAndJoin() } }
            clean { queueDb.close() }; clean { playlistDb.close() }
            preferences.forEach { (store, values) -> clean { restore(store, values) } }
            if (!bodyFailed && primary != null) {
                runCatching { writeManifest("cleanup-failed", primary.toString()) }.onFailure(primary!!::addSuppressed)
                throw primary!!
            }
        }
    }

    private data class Draw(val count: Int, val uptime: Long, val boundsInRoot: Rect, val focus: Boolean)
    private fun view(node: SemanticsNode) = (node.root as ViewRootForTest).view
    private fun surfaces() = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes()

    private fun capture(label: String, route: String, marker: SemanticsMatcher, retainedDialog: Boolean, open: () -> Unit) {
        assertEquals("Fixed route order", labels[requests], label)
        val preceding = surfaces().map(::view)
        val record = JSONObject().put("label", label).put("route", route).put("status", "opening")
            .put("activation", "ordinary production callback through semantics performClick, NOT physical contact")
        records.put(record)
        writeManifest("running")
        var observedView: View? = null
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        val draw = AtomicReference<Draw?>()
        try {
            // Pause only to attach a passive native draw witness to the newly created real window.
            // Frames below seek a public native root, never an image/color or favorable optical result.
            compose.mainClock.autoAdvance = false
            open()
            var initial: SemanticsNode? = null
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                initial = surfaces().singleOrNull { candidate -> preceding.none { it === view(candidate) } }
                initial != null
            }
            val surface = requireNotNull(initial)
            val root = view(surface).also { observedView = it }
            val window = compose.runOnUiThread {
                assertTrue("Actual production Dialog root", root.parent is DialogWindowProvider)
                (root.parent as DialogWindowProvider).window
            }
            compose.runOnUiThread {
                beginSurfaceDrawCollection(root, window)
                val tree = root.viewTreeObserver.also { observer = it }
                val watch = ViewTreeObserver.OnDrawListener {
                    val count = (draw.get()?.count ?: 0) + 1
                    draw.set(Draw(count, SystemClock.uptimeMillis(), surface.boundsInRoot, root.hasWindowFocus()))
                }.also { listener = it }
                tree.addOnDrawListener(watch)
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val routeNode = compose.onNode(marker).assertIsDisplayed()
            compose.awaitExpectedWindowFocus(routeNode)
            val markerNode = routeNode.fetchSemanticsNode()
            assertSame("Marker identifies this new native window, not its retiring caller", root, view(markerNode))
            record.put("routeMarkerSemantics", routeNode.printToString(maxDepth = 3))
                .put("routeMarkerBoundsInRoot", compose.runOnUiThread { rect(markerNode.boundsInRoot) })
                .put("routeMarkerViewIdentity", id(root))
            compose.waitUntil(5000) {
                val current = surfaces().singleOrNull()?.takeIf { view(it) === root }
                current != null && compose.runOnUiThread {
                    val last = draw.get()
                    root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() && !root.isLayoutRequested &&
                        last != null && last.focus && last.boundsInRoot == current.boundsInRoot && !current.boundsInRoot.isEmpty
                }
            }
            assertEquals("The old menu must retire before the one settled-surface capture", 1, surfaces().size)
            val finalSurface = surfaces().single()
            assertSame(root, view(finalSurface))
            val before = facts(root, window, finalSurface, draw.get(), retainedDialog)
            record.put("before", before).put("beforeSemanticGeometry", semanticGeometry(label, root))
                .put("status", "copy-requested")
            writeManifest("running")
            requests++
            val bitmap = copyOnce(window)
            try {
                val original = File(output, "$label-original-window.png")
                // Persist the complete original buffer BEFORE any pixel inspection; no crop replaces it.
                original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("image", original.name).put("bytes", original.length()).put("sha256", sha256(original))
                    .put("imageWidth", bitmap.width).put("imageHeight", bitmap.height)
                    .put("bitmapConfig", bitmap.config.toString()).put("hasAlpha", bitmap.hasAlpha())
                    .put("premultiplied", bitmap.isPremultiplied)
                // Deliberately no optical thresholds, pixel scanning, image-based readiness or absence assertion.
            } finally { bitmap.recycle() }
            val after = facts(root, window, surfaces().single { view(it) === root }, draw.get(), retainedDialog)
            val beforeSource = before.opt("ordinaryHostBackdropCandidate") as? JSONObject
            val afterSource = after.opt("ordinaryHostBackdropCandidate") as? JSONObject
            record.put("after", after).put("afterSemanticGeometry", semanticGeometry(label, root))
                .put("prePostBoundsIdentical", before.getJSONArray("surfaceBoundsInWindow").toString() ==
                    after.getJSONArray("surfaceBoundsInWindow").toString())
                .put("prePostHostCandidateSerializedIdentical", beforeSource != null && afterSource != null && beforeSource.toString() == afterSource.toString())
                .put("prePostQualification", "Serialized candidate metadata equality is non-atomic with copying and does not prove identical pixels or consumed source; UNKNOWN fields remain UNKNOWN.")
                .put("status", "original-retained")
            writeManifest("running")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            compose.mainClock.autoAdvance = true
            val watch = listener
            val tree = observer
            if (watch != null) compose.runOnUiThread {
                observedRootIdentity = null; observedWindowIdentity = null
                if (tree?.isAlive == true) tree.removeOnDrawListener(watch)
                else observedView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(watch)
            }
        }
    }

    /** Independent public semantic bounds; never relabel them as child material receipts. */
    private fun semanticGeometry(label: String, root: View): JSONArray {
        val names = when (label) {
            "library-utility" -> listOf("Scan music", "Settings")
            "playlist-plus" -> listOf("Create playlist", "Import playlist from file")
            else -> listOf("Sort", "View", "Sort by")
        }
        return JSONArray(names.map { name ->
            val nodes = compose.onAllNodes(hasText(name, substring = false), useUnmergedTree = true)
                .fetchSemanticsNodes().filter { view(it) === root }
            JSONObject().put("name", name).put("count", nodes.size).put("boundsInRoot",
                JSONArray(nodes.map { node -> rect(node.boundsInRoot) }))
                .put("qualification", "semantic bounds only; not outer or child material draw extents")
        })
    }

    /** Row means of original Window RGBA, not band detection or an inter-family comparison.
     * Sampling starts at each real edge and uses that caller's own density. The left/right
     * columns can cross rounded clips; no pixels are composited against an assumed background. */
    private fun writeEdgeProfiles() {
        File(output, "edge-profiles.csv").bufferedWriter().use { out ->
            out.appendLine("caller,edge,region,row_y,distance_dp,x_start,x_end_exclusive,pixels,mean_r,mean_g,mean_b,mean_a")
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val before = record.getJSONObject("before")
                val b = before.getJSONArray("surfaceBoundsInWindow")
                val left = b.getDouble(0).toFloat(); val top = b.getDouble(1).toFloat()
                val right = b.getDouble(2).toFloat(); val bottom = b.getDouble(3).toFloat()
                val density = before.getDouble("density").toFloat()
                require(density.isFinite() && density > 0f)
                val bitmap = requireNotNull(BitmapFactory.decodeFile(File(output, record.getString("image")).path))
                try {
                    val width = right - left
                    val depth = minOf(24f * density, (bottom - top) / 2f)
                    val columns = listOf("left" to (0f to .15f), "center" to (.40f to .60f), "right" to (.85f to 1f))
                    for (edge in listOf("top", "bottom")) {
                        val y0 = ceil(if (edge == "top") top else bottom - depth).toInt()
                        val y1 = floor(if (edge == "top") top + depth else bottom).toInt()
                        for ((region, fractions) in columns) {
                            val x0 = ceil(left + width * fractions.first).toInt()
                            val x1 = floor(left + width * fractions.second).toInt()
                            require(x0 >= 0 && y0 >= 0 && x1 <= bitmap.width && y1 <= bitmap.height && x1 > x0 && y1 > y0)
                            for (y in y0 until y1) {
                                val sums = LongArray(4)
                                for (x in x0 until x1) {
                                    val color = bitmap.getPixel(x, y)
                                    sums[0] += PixelColor.red(color); sums[1] += PixelColor.green(color)
                                    sums[2] += PixelColor.blue(color); sums[3] += PixelColor.alpha(color)
                                }
                                val n = x1 - x0
                                val distance = if (edge == "top") (y + .5f - top) / density else (bottom - y - .5f) / density
                                out.appendLine(listOf(record.getString("label"), edge, region, y, distance,
                                    x0, x1, n, *sums.map { it.toDouble() / n }.toTypedArray()).joinToString(","))
                            }
                        }
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun dismiss() {
        pressSystemBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { surfaces().isEmpty() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("browse-background"))
    }

    /** Main-thread owner observations before/after PixelCopy, explicitly NOT atomic with it. */
    private fun facts(root: View, window: Window, node: SemanticsNode, draw: Draw?, retained: Boolean) = compose.runOnUiThread {
        assertTrue("Record coordinate conversion only for an untransformed native root", root.matrix.isIdentity)
        val inWindow = IntArray(2).also(root::getLocationInWindow)
        val onScreen = IntArray(2).also(root::getLocationOnScreen)
        val bounds = node.boundsInRoot
        val screenBounds = bounds.translate(Offset(onScreen[0].toFloat(), onScreen[1].toFloat()))
        val windowBounds = bounds.translate(Offset(inWindow[0].toFloat(), inWindow[1].toFloat()))
        assertTrue("Measured surface is inside the captured native window", !windowBounds.isEmpty &&
            windowBounds.left >= 0f && windowBounds.top >= 0f &&
            windowBounds.right <= window.decorView.width && windowBounds.bottom <= window.decorView.height)
        val entry = if (retained) host.entries.single { it.mounted } else null
        val anchor = entry?.anchor
        JSONObject().put("observationUptime", SystemClock.uptimeMillis()).put("nonAtomicWithCopy", true)
            .put("viewIdentity", id(root)).put("windowIdentity", id(window)).put("tokenIdentity", id(root.windowToken))
            .put("attached", root.isAttachedToWindow).put("focused", root.hasWindowFocus())
            .put("layoutRequested", root.isLayoutRequested).put("viewSize", JSONArray(listOf(root.width, root.height)))
            .put("decorSize", JSONArray(listOf(window.decorView.width, window.decorView.height)))
            .put("viewOriginInWindow", JSONArray(inWindow.toList())).put("viewOriginOnScreen", JSONArray(onScreen.toList()))
            .put("density", root.resources.displayMetrics.density).put("fontScale", root.resources.configuration.fontScale)
            .put("surfaceBoundsInRoot", rect(bounds))
            .put("surfaceBoundsInWindow", rect(windowBounds))
            .put("surfaceBoundsOnScreen", rect(screenBounds))
            .put("nativeDraw", draw?.let { JSONObject().put("count", it.count).put("uptime", it.uptime)
                .put("focused", it.focus).put("boundsInRoot", rect(it.boundsInRoot)) } ?: "UNKNOWN")
            .put("hostEntries", host.entries.size).put("retainedDialog", retained)
            .put("placement", entry?.placement?.name ?: "UNKNOWN: caller-private direct menu")
            .put("expansion", anchor?.contact?.expansion?.value ?: "UNKNOWN: caller-private direct menu")
            .put("bodySettled", anchor?.contact?.state?.momentum?.settled ?: "UNKNOWN: caller-private direct menu")
            .put("shadowOwnerInModal", anchor?.shadowInModal ?: "UNKNOWN: caller-private direct menu")
            .put("triggerHidden", anchor?.hidden ?: "UNKNOWN: caller-private direct menu")
            .put("anchorBounds", anchor?.bounds?.let(::rect) ?: "UNKNOWN")
            .put("anchorSource", sourceFacts(anchor?.source, screenBounds))
            .put("ordinaryHostBackdropCandidate", sourceFacts(host.backdrop, screenBounds))
            .put("actualNativeSourceConsumption", "UNKNOWN: no per-consumer receipt from these callers")
            .put("homeFootprintAndWeight", "UNKNOWN: no direct-menu anchor or frame-correlated sampling receipt")
            .put("shadowRasterConsumption", "UNKNOWN: configured shadow is not a native consumption receipt")
            .put("surfaceDrawObservations", surfaceDrawFacts(root, window, bounds, draw))
    }

    private fun beginSurfaceDrawCollection(root: View, window: Window) {
        surfaceDraws.clear(); surfaceDrawsDropped = 0
        observedRootIdentity = System.identityHashCode(root)
        observedWindowIdentity = System.identityHashCode(window)
        previousFactsSequence = surfaceDrawSequence
        collectionStartedUptime = SystemClock.uptimeMillis()
    }

    private fun collectSurfaceDraw(frame: GlassSurfaceDrawObservation) {
        if (frame.viewIdentity != observedRootIdentity || frame.windowIdentity != observedWindowIdentity) return
        if (surfaceDraws.size == surfaceDrawLimit) { surfaceDraws.removeFirst(); surfaceDrawsDropped++ }
        surfaceDraws.addLast(SurfaceDraw(++surfaceDrawSequence, frame))
    }

    /** No waits/assertions depend on these observations, including missing or cached entries. */
    private fun surfaceDrawFacts(root: View, window: Window, bounds: Rect, native: Draw?): JSONObject {
        val baseline = previousFactsSequence
        val entries = surfaceDraws.filter { it.frame.viewIdentity == System.identityHashCode(root) &&
            it.frame.windowIdentity == System.identityHashCode(window) }
        previousFactsSequence = surfaceDrawSequence
        fun finite(value: Float): Any = if (value.isFinite()) value else "NONFINITE:$value"
        fun numbers(values: List<Float>?): Any = values?.let { JSONArray(it.map(::finite)) } ?: "UNKNOWN"
        fun source(value: GlassSurfaceSourceDraw?): Any = value?.let { f ->
            JSONObject().put("identity", f.identity).put("parentIdentity", f.parentIdentity ?: "NONE")
                .put("layerIdentity", f.layerIdentity).put("parentLayerIdentity", f.parentLayerIdentity ?: "NONE")
                .put("recorded", f.recorded).put("released", f.released)
                .put("parentRecorded", f.parentRecorded ?: "NONE").put("parentReleased", f.parentReleased ?: "NONE")
                .put("materialOnly", f.materialOnly).put("dark", f.dark)
                .put("extent", JSONArray(listOf(f.width, f.height))).put("layerExtent", JSONArray(listOf(f.layerWidth, f.layerHeight)))
                .put("revision", f.revision).put("requestedReadback", f.requestedReadback).put("publishedReadback", f.publishedReadback)
                .put("publishedDrawRequest", f.publishedDrawRequest ?: "UNKNOWN").put("publishedComposite", f.publishedComposite ?: "UNKNOWN")
                .put("snapshotIdentity", f.snapshotIdentity ?: "NONE").put("frostIdentity", f.frostIdentity ?: "NONE")
                .put("lensIdentity", f.lensIdentity ?: "NONE").put("sourceToConsumerAffine", numbers(f.sourceToConsumer))
                .put("unrefractedConsumerCornersInSource", numbers(f.consumerCornersInSource))
        } ?: "NONE: no supplied source"
        fun matchedBounds(frame: GlassSurfaceDrawObservation): Boolean {
            val points = frame.rootCorners ?: return false
            if (points.size != 8) return false
            val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
            val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
            return Rect(xs.min(), ys.min(), xs.max(), ys.max()) == bounds
        }
        return JSONObject().put("collectionStartedUptime", collectionStartedUptime)
            .put("limit", surfaceDrawLimit).put("droppedOldest", surfaceDrawsDropped)
            .put("previousFactsSequence", baseline).put("lastSequence", surfaceDrawSequence)
            .put("status", when {
                entries.isEmpty() -> "MISSING: no matching surface traversal observed; no backfill or redraw"
                entries.none { it.sequence > baseline } -> "CACHED: retained observations only, no new traversal since previous facts"
                else -> "NEW_TRAVERSALS: not proof of fresh filter execution or native consumption"
            })
            .put("rootMatchedCount", entries.size).put("exactOuterBoundsMatchCount", entries.count { matchedBounds(it.frame) })
            .put("observations", JSONArray(entries.map { entry ->
                val f = entry.frame
                JSONObject().put("sequence", entry.sequence).put("newSincePreviousFacts", entry.sequence > baseline)
                    .put("uptime", f.uptime).put("surfaceIdentity", f.surfaceIdentity)
                    .put("viewIdentity", f.viewIdentity).put("windowIdentity", f.windowIdentity ?: "UNKNOWN")
                    .put("tokenIdentity", f.tokenIdentity ?: "UNKNOWN").put("attached", f.attached).put("focused", f.focused)
                    .put("viewMatrixIdentity", f.viewMatrixIdentity).put("visible", f.visible).put("drawExtent", JSONArray(listOf(finite(f.width), finite(f.height))))
                    .put("rootCorners", numbers(f.rootCorners)).put("windowCorners", numbers(f.windowCorners))
                    .put("screenCorners", numbers(f.screenCorners)).put("computedSilhouetteBounds", numbers(f.outlineBounds))
                    .put("exactOuterLayoutBoundsMatch", matchedBounds(f))
                    .put("nativeDrawAssociation", when {
                        native == null -> "UNKNOWN: no native draw witness"
                        f.uptime < native.uptime -> "CACHED_OR_EARLIER: observation predates latest native draw witness"
                        else -> "SAME_OR_LATER_MILLISECOND: ordering only, NOT a shared frame or consumption token"
                    })
                    .put("source", source(f.source)).put("home", source(f.home))
                    .put("inkArgb", f.inkArgb).put("inkOverridden", f.inkOverridden).put("lightInk", f.lightInk)
                    .put("coatingDark", f.coatingDark).put("toneTrim", finite(f.toneTrim)).put("toneWeight", finite(f.toneWeight))
                    .put("tintArgb", f.tintArgb).put("panel", finite(f.panel)).put("frost", finite(f.frost))
                    .put("suppliedHomeWeight", finite(f.suppliedHomeWeight)).put("blur", finite(f.blur))
                    .put("whiteTint", finite(f.whiteTint)).put("depth", finite(f.depth)).put("depthScale", finite(f.depthScale))
                    .put("refraction", finite(f.refraction)).put("surfaceShadowEnabled", f.shadowEnabled)
                    .put("configuredShadowStrength", finite(f.shadowStrength)).put("configuredShadowSpread", finite(f.shadowSpread))
                    .put("shadowGroupIdentity", f.shadowGroupIdentity ?: "NONE").put("shadowInModal", f.shadowInModal ?: "NO_ANCHOR")
                    .put("silhouettePresent", f.silhouettePresent)
            }))
            .put("nonAtomicWithCopy", true)
            .put("qualification", "Bounded scalar coating-Canvas traversals in this measured popup root/window, including nested controls. Exact layout-bounds matches are candidates, not unique surface or raster proof. Identity hashes can collide. Pre/post copy observations are not atomic; cached draws may emit nothing. No observation controls readiness.")
            .put("unknown", "Actual filter crop/averaging/parent branch, admitted home blend, sampled revision, ancestor draw-only deformation/clip/opacity, outer caller-owned shadow and native shader/compositor consumption remain UNKNOWN. Supplied homeWeight is re-read at coating traversal, not copied from private filter execution. Corners use layout registration, not refracted rays or optical crop.")
    }

    private fun sourceFacts(source: FrostedBackdrop?, screen: Rect): Any {
        if (source == null) return "UNKNOWN"
        val projection = source.location.registrationToScreen()?.inverse()?.corners(screen)
        return JSONObject().put("identity", id(source)).put("parentIdentity", id(source.parent))
            .put("layerIdentity", id(source.layer)).put("parentLayerIdentity", id(source.parent?.layer))
            .put("layerExtent", JSONArray(listOf(source.layer.size.width, source.layer.size.height)))
            .put("layerReleased", source.layer.isReleased)
            .put("recorded", source.recorded).put("materialOnly", source.materialOnly).put("dark", source.dark)
            .put("size", JSONArray(listOf(source.size.width, source.size.height)))
            .put("origin", JSONArray(listOf(source.origin.x, source.origin.y)))
            .put("revision", source.revision).put("requestedReadback", source.readbackRequest)
            .put("publishedReadback", source.snapshotReadbackRequest)
            .put("snapshotIdentity", id(source.snapshot)).put("frostIdentity", id(source.fallback))
            .put("lensIdentity", id(source.lensSnapshot)).put("drawReceipt", source.snapshotDraws?.toString() ?: "UNKNOWN")
            .put("unrefractedSurfaceCornersInCandidateSource", projection?.let { corners ->
                JSONArray(corners.map { JSONArray(listOf(it.x, it.y)) })
            } ?: "UNKNOWN")
            .put("qualification", "candidate owner recording; projection is not an optical crop or proof of consumed pixels")
    }

    private fun copyOnce(window: Window): Bitmap {
        val image = compose.runOnUiThread {
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        val completed = CountDownLatch(1)
        val ownership = Any()
        var returned = false
        var abandoned = false
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(window, image, { code ->
                synchronized(ownership) { result = code; returned = true; if (abandoned) image.recycle() }
                completed.countDown()
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) { image.recycle(); throw failure }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            synchronized(ownership) { if (returned) image.recycle() else abandoned = true }
            throw AssertionError("Single original window copy timed out; no retry")
        }
        if (result != PixelCopy.SUCCESS) { image.recycle(); throw AssertionError("Single original window copy failed: $result; no retry") }
        return image
    }

    private fun writeManifest(status: String, failure: String? = null) {
        File(output, "manifest.json").writeText(JSONObject().put("status", status).put("failure", failure ?: JSONObject.NULL)
            .put("api", Build.VERSION.SDK_INT).put("declaredTotalCaptures", 3).put("requestedCopies", requests)
            .put("fixedOrder", JSONArray(labels)).put("records", records)
            .put("fixture", "Actual LocalicaScreen/TagEditorHost; synthetic three-song album; real in-memory Queue/Playlist controllers; default InterfaceSettings; no playback service attached")
            .put("configuredAppearance", InterfaceSettings().glass.toString())
            .put("readiness", "Default motion; clock paused only to install native draw witness on new production window, then ordinary Compose idle + focus/layout/draw. No forced redraw.")
            .put("limitations", "Presentation diagnostics, NOT band acceptance. No physical display/compositor identity, native sampled revision, direct-menu expansion/anchor/shadow receipt, or cross-family equal-crop claim. No image-driven readiness or optical thresholds. Original full window buffers retained before profiles; only ownership/route/readiness assertions. Profiles use Android BitmapFactory/getPixel decoded RGBA and each caller's own bounds; left/right columns can include rounded-clip transparency and all strips may include foreground. Different geometry and source crop are not normalized or matched. No native shader consumption proof.")
            .toString(2))
    }

    private fun rect(bounds: Rect) = JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
    private fun id(value: Any?): Any = value?.let(System::identityHashCode) ?: "UNKNOWN"
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun restore(store: SharedPreferences, values: Map<String, *>) {
        val edit = store.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            null -> edit.remove(key)
            else -> error("Unsupported preference type: ${value.javaClass.name}")
        } }
        assertTrue("Restore exact fixture preferences", edit.commit())
    }
}
