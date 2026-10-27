package io.github.anastaci1a.localica

import android.content.SharedPreferences
import android.graphics.Bitmap
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
import androidx.compose.ui.layout.LayoutInfo
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Same actual Library utility popup, not a recreated menu or primitive substitute. Exactly THREE
 * original native Window copies: ordinary child shadows -> zero -> ordinary restored. Only child
 * shadow strength changes. No image-driven wait, redraw, extra source readback or optical threshold.
 * Current public host/source facts qualify supplied-owner stability, NOT fresh shader consumption.
 * An outer coating receipt may remain cached; it is never relabeled as a fresh frame. Missing or
 * changed source/geometry/provenance fails the matched-control claim after retaining the originals.
 * The first ordinary image is unbound context only; only zero and restored are compared. */
class PopupContentShadowBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ordinary = InterfaceSettings().glass.shadowStrength
    private var strength by mutableFloatStateOf(ordinary)
    private var mounted by mutableStateOf(true)
    private lateinit var host: GlassDialogs
    private lateinit var root: View
    private lateinit var window: Window
    private lateinit var output: File
    private val records = JSONArray()
    private var copies = 0
    private data class Draw(val count: Int, val uptime: Long, val requestedStrength: Float)
    private val drawn = AtomicReference<Draw?>()
    // Plain Main-thread bounded metadata; no Snapshot writes or retained graphics resources.
    private data class Observed(val sequence: Long, val frame: GlassSurfaceDrawObservation)
    private val observed = linkedMapOf<Int, Observed>()
    private var sequence = 0L
    private val lastSerializedReceipt = mutableMapOf<Int, Long>()
    private var observedRoot: Int? = null
    private val tracks = (1..3).map { index -> Track("child-shadow:$index", "Diagnostic song $index",
        artist = "Diagnostic artist", album = "Diagnostic album", fileName = "$index.mp3",
        relativePath = "Diagnostic album/$index.mp3", locations = listOf(TrackLocation(
            "child-shadow-root", "child-shadow:$index", "Diagnostic album/$index.mp3"))) }

    @Test fun sameUtilityPopupRecordsOrdinaryZeroAndRestoredChildShadowMargins() {
        val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null))
        output = File(base, "popup-content-shadow-boundary/api${Build.VERSION.SDK_INT}-${SystemClock.uptimeMillis()}")
        assertTrue(output.mkdirs())
        assertEquals("Ordinary child shadow control is the specified default", .12f, ordinary, 0f)
        writeManifest("starting")
        val preferences = listOf("interface", "browse-style", "library-query", "library-filters", "pins", "queue-settings")
            .associate { name -> context.getSharedPreferences(name, 0).let { it to it.all.toMap() } }
        val playlistDb = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val images = mutableListOf<Bitmap>()
        var contentInstalled = false
        var tree: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        var primary: Throwable? = null
        try {
            preferences.keys.forEach { assertTrue(it.edit().clear().commit()) }
            InterfacePreferences(context).write(InterfaceSettings())
            val app = context.applicationContext as LocalicaApplication
            val editor = app.tagEditing.state.value
            assertTrue("Do not alter existing editor/recovery/job state", editor.tracks.isEmpty() &&
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
            val catalog = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("child-shadow-root", "Diagnostic music")), tracks), loading = false)
            compose.setContent {
                val inherited = LocalGlassSurfaceDrawObserver.current
                val collect = remember(inherited) { { frame: GlassSurfaceDrawObservation ->
                    try { inherited?.invoke(frame) } catch (_: Throwable) { }
                    if (frame.viewIdentity == observedRoot) {
                        if (frame.surfaceIdentity !in observed && observed.size == 64) observed.remove(observed.keys.first())
                        observed[frame.surfaceIdentity] = Observed(++sequence, frame)
                    }
                } }
                CompositionLocalProvider(LocalGlassSurfaceDrawObserver provides collect,
                    LocalPopupContentShadowStrength provides strength) {
                    if (mounted) LocalicaTheme {
                        val ordinaryHost = requireNotNull(LocalGlassDialogs.current)
                        SideEffect { host = ordinaryHost }
                        val playlistState by playlists.state.collectAsState()
                        val queueState by queues.state.collectAsState()
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
            // Same initial root-admission procedure/deadline as the actual-family diagnostic.
            // No phase after this point closes, reopens or reconstructs the menu.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("More", substring = false).performClick()
            var initial: SemanticsNode? = null
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                initial = surfaces().singleOrNull()
                initial != null
            }
            root = view(requireNotNull(initial))
            compose.runOnUiThread {
                assertTrue(root.parent is DialogWindowProvider)
                window = (root.parent as DialogWindowProvider).window
                observedRoot = System.identityHashCode(root)
                tree = root.viewTreeObserver
                listener = ViewTreeObserver.OnDrawListener {
                    drawn.set(Draw((drawn.get()?.count ?: 0) + 1, SystemClock.uptimeMillis(), strength))
                }.also { tree!!.addOnDrawListener(it) }
            }
            compose.mainClock.autoAdvance = true
            val before = mutableListOf<Facts>()
            val after = mutableListOf<Facts>()
            val phases = listOf("ordinary", "child-zero", "ordinary-restored")
            val strengths = listOf(ordinary, 0f, ordinary)
            phases.forEachIndexed { index, label ->
                val precedingDraw = compose.runOnUiThread {
                    val count = if (index == 0) 0 else drawn.get()?.count ?: 0
                    strength = strengths[index]
                    count
                }
                awaitDraw(precedingDraw, strengths[index])
                val start = facts().also(before::add)
                val record = JSONObject().put("phase", label).put("requestedChildStrength", strengths[index])
                    .put("before", json(start)).put("status", "copy-requested")
                records.put(record); writeManifest("running")
                copies++
                val image = copyOnce().also(images::add)
                val original = File(output, "$label-original-window.png")
                // Preserve complete RGBA-capable originals before inspecting ANY pixel.
                original.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("image", original.name).put("bytes", original.length()).put("sha256", sha256(original))
                    .put("imageWidth", image.width).put("imageHeight", image.height)
                    .put("bitmapConfig", image.config.toString()).put("hasAlpha", image.hasAlpha())
                    .put("premultiplied", image.isPremultiplied)
                val finish = facts().also(after::add)
                record.put("after", json(finish)).put("status", "original-retained")
                writeManifest("running")
            }
            assertEquals("Exactly three fixed original copies, no replacements", 3, copies)
            // Do not salvage a matched comparison by retrying, redrawing or selecting another frame.
            // Ordinary has no bound child receipt in the prior native run. Keep its original
            // and facts as explicitly unbound context, never a baseline or comparison.
            val baseline = before[1]
            listOf(1, 2).forEach { index ->
                assertStable(baseline, before[index], strengths[index])
                assertStable(baseline, after[index], strengths[index])
            }
            val regions = margins(baseline)
            writeProfiles(images[1], images[2], regions)
            writeManifest("matched-zero-restored-supplied-owner-control-NOT-optical-acceptance")
        } catch (failure: Throwable) {
            primary = failure
            runCatching { writeManifest("failed-or-unmatched-source-control", failure.toString()) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary!!.addSuppressed(failure)
            } }
            val bodyFailed = primary != null
            clean { compose.mainClock.autoAdvance = true }
            if (contentInstalled) clean { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
            clean { compose.runOnUiThread {
                observedRoot = null
                listener?.let { watch ->
                    if (tree?.isAlive == true) tree!!.removeOnDrawListener(watch)
                    else if (::root.isInitialized) root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(watch)
                }
            } }
            images.forEach { image -> clean { image.recycle() } }
            clean { runBlocking { job.cancelAndJoin() } }
            clean { queueDb.close() }; clean { playlistDb.close() }
            preferences.forEach { (store, values) -> clean { restore(store, values) } }
            if (!bodyFailed && primary != null) {
                runCatching { writeManifest("cleanup-failed", primary.toString()) }.onFailure(primary!!::addSuppressed)
                throw primary!!
            }
        }
    }

    private fun surfaces() = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes()
    private fun view(node: SemanticsNode) = (node.root as ViewRootForTest).view
    private fun awaitDraw(after: Int, expected: Float) {
        compose.waitForIdle()
        val marker = compose.onNodeWithText("Scan music", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Settings", substring = false).assertIsDisplayed()
        compose.awaitExpectedWindowFocus(marker)
        assertSame("The same original popup owns the marker", root, view(marker.fetchSemanticsNode()))
        compose.waitUntil(5000) { compose.runOnUiThread {
            val draw = drawn.get()
            root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() && !root.isLayoutRequested &&
                draw != null && draw.count > after && draw.requestedStrength == expected
        } }
        assertEquals("No replacement or second popup", 1, surfaces().size)
        assertSame(root, view(surfaces().single()))
    }

    private data class Owner(val id: Int, val layout: LayoutInfo)
    private data class Facts(val uptime: Long, val bounds: Map<String, Rect>, val owners: Map<String, Owner>,
        val inWindow: Offset, val onScreen: Offset, val token: Any?, val source: FrostedBackdrop?,
        val liveSource: GlassSurfaceSourceDraw?, val frames: List<Observed>, val draw: Draw?)
    private fun facts(): Facts {
        val nodes = linkedMapOf("outer" to surfaces().single(),
            "Scan music" to compose.onNode(hasText("Scan music") and hasClickAction()).fetchSemanticsNode(),
            "Settings" to compose.onNode(hasText("Settings") and hasClickAction()).fetchSemanticsNode())
        return compose.runOnUiThread {
            nodes.values.forEach { assertSame(root, view(it)) }
            assertSame("Original native Window remains the current parent owner", window, (root.parent as DialogWindowProvider).window)
            assertNotNull("Attached popup token is independently available", root.windowToken)
            assertTrue("Only an untransformed native root is converted", root.matrix.isIdentity)
            val iw = IntArray(2).also(root::getLocationInWindow)
            val screen = IntArray(2).also(root::getLocationOnScreen)
            val screenOrigin = Offset(screen[0].toFloat(), screen[1].toFloat())
            val bounds = nodes.mapValues { (_, node) -> layoutBounds(node.layoutInfo) }
            val coordinates = nodes.getValue("outer").layoutInfo.coordinates
            val toScreen = BackdropRegistration.fromMapping(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()) {
                coordinates.localToRoot(it) + screenOrigin
            }
            val source = host.backdrop
            val registration = source?.location?.registrationToScreen()?.let { a -> toScreen?.inverse()?.let(a::followedBy) }
            Facts(SystemClock.uptimeMillis(), bounds, nodes.mapValues { (_, node) -> Owner(node.id, node.layoutInfo) },
                Offset(iw[0].toFloat(), iw[1].toFloat()), screenOrigin, root.windowToken, source,
                sourceState(source, registration, bounds.getValue("outer")),
                observed.values.filter { it.frame.viewIdentity == System.identityHashCode(root) &&
                    it.frame.windowIdentity == System.identityHashCode(window) }.toList(), drawn.get())
        }
    }

    private fun sourceState(source: FrostedBackdrop?, registration: BackdropRegistration?, bounds: Rect): GlassSurfaceSourceDraw? {
        source ?: return null
        return GlassSurfaceSourceDraw(System.identityHashCode(source), id(source.parent), System.identityHashCode(source.layer), id(source.parent?.layer),
            source.recorded, source.layer.isReleased, source.parent?.recorded, source.parent?.layer?.isReleased,
            source.materialOnly, source.dark, source.size.width, source.size.height, source.layer.size.width, source.layer.size.height,
            source.revision, source.readbackRequest, source.snapshotReadbackRequest, source.snapshotDraws?.request, source.snapshotDraws?.composite,
            id(source.snapshot), id(source.fallback), id(source.lensSnapshot),
            registration?.let { listOf(it.xx, it.xy, it.yx, it.yy, it.tx, it.ty) },
            registration?.inverse()?.corners(Rect(0f, 0f, bounds.width, bounds.height))?.flatMap { listOf(it.x, it.y) })
    }

    private fun layoutBounds(info: LayoutInfo): Rect {
        val c = info.coordinates
        assertTrue(c.isAttached)
        val points = listOf(Offset.Zero, Offset(c.size.width.toFloat(), 0f),
            Offset(c.size.width.toFloat(), c.size.height.toFloat()), Offset(0f, c.size.height.toFloat())).map(c::localToRoot)
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
    private fun frameBounds(frame: GlassSurfaceDrawObservation): Rect? = frame.rootCorners?.takeIf { it.size == 8 }?.let {
        val xs = it.filterIndexed { index, _ -> index % 2 == 0 }; val ys = it.filterIndexed { index, _ -> index % 2 == 1 }
        Rect(xs.min(), ys.min(), xs.max(), ys.max())
    }
    private fun outer(facts: Facts): Observed {
        val candidates = facts.frames.filter { frameBounds(it.frame) == facts.bounds.getValue("outer") }
        assertEquals("Require one actual outer receipt bound to exact measured bounds; unknown or ambiguous is not a matched control", 1, candidates.size)
        return candidates.single().also { assertFalse("Outer receipt is not a shadow-enabled child", it.frame.shadowEnabled) }
    }
    private fun child(facts: Facts, label: String): Observed {
        val candidates = facts.frames.filter { frameBounds(it.frame) == facts.bounds.getValue(label) }
        assertEquals("Require one child material receipt at exact measured bounds for $label", 1, candidates.size)
        return candidates.single().also { assertTrue("Measured child receipt enables its own shadow for $label", it.frame.shadowEnabled) }
    }
    // Receipt time identifies an observation, not surface state. Strength is the sole
    // surface-state field normalized for the matched child comparison.
    private fun stable(frame: GlassSurfaceDrawObservation) = frame.copy(uptime = 0L)
    private fun assertReceiptOwnerAndGeometry(facts: Facts, receipt: GlassSurfaceDrawObservation, label: String) {
        assertEquals(id(root), receipt.viewIdentity)
        assertEquals(id(window), receipt.windowIdentity)
        assertEquals(id(facts.token), receipt.tokenIdentity)
        assertTrue("Receipt belongs to an attached, untransformed popup", receipt.attached && receipt.viewMatrixIdentity)
        val corners = requireNotNull(receipt.rootCorners) { "Missing root geometry for $label" }
        assertEquals(8, corners.size)
        assertTrue(corners.all { it.isFinite() })
        assertEquals(facts.bounds.getValue(label), frameBounds(receipt))
        assertEquals("Receipt window geometry must match measured native root origin",
            corners.mapIndexed { index, value -> value + if (index % 2 == 0) facts.inWindow.x else facts.inWindow.y }, receipt.windowCorners)
        assertEquals("Receipt screen geometry must match measured native root origin",
            corners.mapIndexed { index, value -> value + if (index % 2 == 0) facts.onScreen.x else facts.onScreen.y }, receipt.screenCorners)
    }
    private fun assertStable(a: Facts, b: Facts, expected: Float) {
        assertEquals("Baseline native draw acknowledges zero strength", 0f, requireNotNull(a.draw).requestedStrength, 0f)
        assertEquals("Current native draw acknowledges requested strength", expected, requireNotNull(b.draw).requestedStrength, 0f)
        assertEquals("Outer and actual child/foreground bounds unchanged", a.bounds, b.bounds)
        assertEquals(a.inWindow, b.inWindow); assertEquals(a.onScreen, b.onScreen); assertSame(a.token, b.token)
        a.owners.forEach { (label, owner) ->
            assertEquals(owner.id, b.owners.getValue(label).id); assertSame(owner.layout, b.owners.getValue(label).layout)
        }
        assertNotNull("Actual utility source must bind to readable public host owner", a.source)
        assertSame("Public source owner cannot change", a.source, b.source)
        val live = requireNotNull(b.liveSource) { "Current public source provenance is unavailable" }
        assertEquals("Live owner, revision, publication and current registration must remain unchanged", a.liveSource, b.liveSource)
        val first = outer(a).frame; val current = outer(b).frame
        assertReceiptOwnerAndGeometry(a, first, "outer"); assertReceiptOwnerAndGeometry(b, current, "outer")
        assertEquals("Bind actual supplied outer source uniquely, not just an earlier candidate", b.liveSource, current.source)
        assertEquals("Outer coating/ink/geometry/supplied source must remain unchanged", stable(first), stable(current))
        assertEquals(1f, current.panel, 0f); assertEquals(1f, current.frost, 0f); assertEquals(0f, current.suppliedHomeWeight, 0f)
        assertFalse(current.shadowEnabled)
        assertTrue(live.recorded); assertFalse(live.released)
        assertNotNull("Unknown registration cannot become a matched control", live.sourceToConsumer)
        assertNotNull(live.consumerCornersInSource)
        listOf("Scan music", "Settings").forEach { label ->
            val initial = child(a, label).frame; val frame = child(b, label).frame
            assertReceiptOwnerAndGeometry(a, initial, label); assertReceiptOwnerAndGeometry(b, frame, label)
            assertEquals("Baseline child receipt must acknowledge zero strength", 0f, initial.shadowStrength, 0f)
            assertEquals("Actual child coating receipt must acknowledge requested strength", expected, frame.shadowStrength, 0f)
            assertEquals("Child material, foreground polarity and registration stay unchanged apart from strength",
                stable(initial), stable(frame).copy(shadowStrength = 0f))
            val childSource = requireNotNull(frame.source) { "Missing child backing for $label" }
            assertEquals("Child backing remains parented to the actual outer source", live.identity, childSource.parentIdentity)
            assertEquals("Child backing parent layer remains the live outer source layer", live.layerIdentity, childSource.parentLayerIdentity)
            assertTrue("Child backing is recorded and not released", childSource.recorded && !childSource.released)
            assertEquals(true, childSource.parentRecorded); assertEquals(false, childSource.parentReleased)
            assertNotNull("Unknown child registration cannot become a matched control", childSource.sourceToConsumer)
            assertNotNull(childSource.consumerCornersInSource)
        }
    }

    private fun margins(facts: Facts): Map<String, Rect> {
        val outer = facts.bounds.getValue("outer")
        // Independent public child material draw extents AND actual clickable foreground layout,
        // not guessed padding, screenshot colors, the shadow's claimed reach or its implementation.
        val exclusions = listOf("Scan music", "Settings").flatMap { label ->
            listOf(facts.bounds.getValue(label), requireNotNull(frameBounds(child(facts, label).frame)))
        }
        exclusions.forEach { assertTrue("All child material/foreground layouts inside outer bounds", it.left >= outer.left &&
            it.right <= outer.right && it.top >= outer.top && it.bottom <= outer.bottom) }
        val regions = linkedMapOf("top" to Rect(outer.left, outer.top, outer.right, exclusions.minOf { it.top }),
            "bottom" to Rect(outer.left, exclusions.maxOf { it.bottom }, outer.right, outer.bottom))
        return regions.mapValues { (_, value) -> value.translate(facts.inWindow).also {
            assertTrue("Measured outside-child margin must contain full pixels", floor(it.bottom) > ceil(it.top) && floor(it.right) > ceil(it.left))
        } }
    }

    private fun writeProfiles(zero: Bitmap, restored: Bitmap, regions: Map<String, Rect>) {
        assertEquals(zero.width, restored.width); assertEquals(zero.height, restored.height)
        File(output, "outer-margin-profiles.csv").bufferedWriter().use { out ->
            out.appendLine("margin,y,x_start,x_end_exclusive,pixels,zero_r,zero_g,zero_b,zero_a,restored_r,restored_g,restored_b,restored_a,mean_abs_rgba_zero_restored")
            regions.forEach { (name, r) ->
                val x0 = ceil(r.left).toInt(); val x1 = floor(r.right).toInt()
                val y0 = ceil(r.top).toInt(); val y1 = floor(r.bottom).toInt()
                assertTrue(x0 >= 0 && y0 >= 0 && x1 <= zero.width && y1 <= zero.height)
                for (y in y0 until y1) {
                    val sums = Array(2) { DoubleArray(4) }; var difference = 0.0
                    for (x in x0 until x1) {
                        val values = listOf(zero, restored).map { image -> image.getPixel(x, y).let { c ->
                            intArrayOf(PixelColor.red(c), PixelColor.green(c), PixelColor.blue(c), PixelColor.alpha(c)) } }
                        for (channel in 0..3) {
                            for (phase in 0..1) sums[phase][channel] += values[phase][channel]
                            difference += abs(values[0][channel] - values[1][channel])
                        }
                    }
                    val n = x1 - x0
                    out.appendLine(listOf(name, y, x0, x1, n).joinToString(",") + "," +
                        sums.flatMap { it.map { value -> value / n } }.joinToString(",") +
                        ",${difference / (n * 4)}")
                }
            }
        }
        File(output, "measured-margin-regions.json").writeText(JSONObject().also { json ->
            regions.forEach { (name, region) -> json.put(name, rect(region)) }
        }.toString(2))
    }

    private fun json(f: Facts): JSONObject = JSONObject().put("uptime", f.uptime).put("nonAtomicWithCopy", true)
        .put("viewIdentity", id(root)).put("windowIdentity", id(window)).put("tokenIdentity", id(f.token))
        .put("rootOriginInWindow", JSONArray(listOf(f.inWindow.x, f.inWindow.y)))
        .put("rootOriginOnScreen", JSONArray(listOf(f.onScreen.x, f.onScreen.y)))
        .put("bounds", JSONObject().also { obj -> f.bounds.forEach { (name, bounds) -> obj.put(name, rect(bounds)) } })
        .put("owners", JSONObject().also { obj -> f.owners.forEach { (name, owner) -> obj.put(name,
            JSONObject().put("semanticsId", owner.id).put("layoutIdentity", id(owner.layout))) } })
        .put("publicHostSourceIdentity", id(f.source))
        .put("livePublicHostSource", f.liveSource?.toString() ?: "UNKNOWN")
        .put("nativeDraw", f.draw?.toString() ?: "UNKNOWN")
        .put("surfaceReceipts", JSONArray(f.frames.map { receipt ->
            val previous = lastSerializedReceipt.put(receipt.frame.surfaceIdentity, receipt.sequence)
            JSONObject().put("sequence", receipt.sequence)
            .put("sincePreviousFacts", when (previous) {
                null -> "FIRST_RETAINED_OBSERVATION; not a fresh-frame guarantee"
                receipt.sequence -> "CACHED; same coating traversal as previous facts"
                else -> "NEW_COATING_TRAVERSAL; not shader consumption"
            })
            .put("uptime", receipt.frame.uptime).put("scalarReceipt", receipt.frame.toString())
            .put("surfaceIdentity", receipt.frame.surfaceIdentity).put("suppliedSourceIdentity", receipt.frame.source?.identity ?: "NONE")
            .put("sourceToConsumerAffine", receipt.frame.source?.sourceToConsumer?.let { JSONArray(it) } ?: "UNKNOWN")
            .put("rootCorners", receipt.frame.rootCorners?.let { JSONArray(it) } ?: "UNKNOWN")
            .put("panel", receipt.frame.panel).put("frost", receipt.frame.frost).put("suppliedHomeWeight", receipt.frame.suppliedHomeWeight)
            .put("inkArgb", receipt.frame.inkArgb).put("coatingDark", receipt.frame.coatingDark)
            .put("shadowStrength", receipt.frame.shadowStrength).put("shadowEnabled", receipt.frame.shadowEnabled)
            .put("nativeDrawAssociation", if (f.draw == null || receipt.frame.uptime < f.draw.uptime)
                "CACHED_OR_EARLIER; not a fresh outer draw" else "Same/later millisecond only; not a shared native frame token") }))
        .put("qualification", "Cached outer coating receipts remain cached. Current public host/source and independent layout registration bind supplied stability only; private filter execution, ancestor draw-only deformation and native shader/compositor consumption remain UNKNOWN.")

    private fun copyOnce(): Bitmap {
        val image = compose.runOnUiThread { Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888) }
        val completed = CountDownLatch(1); val ownership = Any()
        var returned = false; var abandoned = false; var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(window, image, { code ->
                synchronized(ownership) { result = code; returned = true; if (abandoned) image.recycle() }
                completed.countDown()
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) { image.recycle(); throw failure }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            synchronized(ownership) { if (returned) image.recycle() else abandoned = true }
            throw AssertionError("One original copy timed out; no retry")
        }
        if (result != PixelCopy.SUCCESS) { image.recycle(); throw AssertionError("One original copy failed: $result; no retry") }
        return image
    }
    private fun writeManifest(status: String, failure: String? = null) {
        File(output, "manifest.json").writeText(JSONObject().put("status", status).put("failure", failure ?: JSONObject.NULL)
            .put("api", Build.VERSION.SDK_INT).put("declaredTotalCaptures", 3).put("requestedCopies", copies)
            .put("phaseOrder", JSONArray(listOf("ordinary", "child-zero", "ordinary-restored")))
            .put("ordinaryChildStrength", ordinary).put("records", records)
            .put("requestedComparisonPhases", JSONArray(listOf("child-zero", "ordinary-restored")))
            .put("ordinaryQualification", "ordinary original and metadata are unbound context only; not compared")
            .put("scope", "Actual LocalicaScreen Library More utility caller, same open native popup; synthetic three-song album and real in-memory controllers. Only opt-in child shadowStrength changes, never outer material/coating/shadow.")
            .put("readiness", "Existing actual-family root admission, Compose idle/native focus plus bounded 5000ms native layout/draw readiness; no image or source-value polling, forced draw or extra readback.")
            .put("limits", "Descriptive outside-child top/bottom RGBA profiles, NOT band absence or an optical fix. Full original Window PNGs retained before pixels; alpha is not flattened. Supplied owner stability is non-atomic with copying and does not prove fresh filter or shader consumption. Zero/restored require unique exact-bounds receipts and unchanged binding before and after each copy; any missing or changed binding fails the comparison without replacements. Ordinary is unbound context, not compared.")
            .toString(2))
    }
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun id(value: Any?): Int? = value?.let(System::identityHashCode)
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
