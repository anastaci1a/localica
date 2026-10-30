package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Header-only shadow attribution control, NOT a seam fix or an optical acceptance test.
 * Exactly TWO original native activity-window copies: ordinary nonzero header strength, then 0.
 * Both states use the opt-in provider so changing its value cannot switch composition branches.
 * The null/default production route remains without that provider. No material/geometry change,
 * extra source readback, capture retry, pixel-driven readiness or optical pass/fail threshold.
 * Native draw/owner state is not atomic with PixelCopy and does not attest GPU/raster consumption. */
class QueueHeaderMaterialBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val appearance = GlassAppearance()
    private val tracks = (1..18).map { Track("queue-boundary:$it", "Boundary song $it",
        artist = "Boundary artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val queue = QueueEdits.create(tracks, 0, "Boundary queue")
    private val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
    private val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
    private val selection = SessionSelection()
    private val progress = Animatable(1f)
    private var strength by mutableFloatStateOf(appearance.shadowStrength)
    private var mounted by mutableStateOf(true)
    private lateinit var root: View
    private lateinit var backing: FrostedBackdrop
    private lateinit var material: FrostedBackdrop
    private lateinit var host: GlassDialogs
    private var scene: BrowseBackdropScene? = null
    private var ambient: GlassAppearance? = null
    private var settledCallbacks = 0
    private var minimizeCallbacks = 0
    private data class Draw(val count: Int, val uptime: Long, val requestedStrength: Float)
    private val drawn = AtomicReference<Draw?>()
    private lateinit var output: File
    private val records = JSONArray()
    private var copies = 0

    @Test fun ordinaryAndHeaderZeroShadowsRetainOwnersAndRecordPairedBoundaryBands() {
        assertTrue("Ordinary control is known nonzero, not an already-disabled comparison", appearance.shadowStrength > 0f)
        val context = compose.activity
        output = File(requireNotNull(context.getExternalFilesDir(null)), "queue-header-boundary/api${Build.VERSION.SDK_INT}-${SystemClock.uptimeMillis()}")
        assertTrue(output.mkdirs())
        val preferences = listOf("browse-style", "library-query").associate { name ->
            context.getSharedPreferences(name, 0).let { it to it.all.toMap() }
        }
        val tree = compose.runOnUiThread { compose.activity.window.decorView.viewTreeObserver }
        val listener = ViewTreeObserver.OnDrawListener {
            drawn.set(Draw((drawn.get()?.count ?: 0) + 1, SystemClock.uptimeMillis(), strength))
        }
        var first: Bitmap? = null
        var second: Bitmap? = null
        var contentInstalled = false
        var primary: Throwable? = null
        try {
            preferences.keys.forEach { assertTrue(it.edit().clear().commit()) }
            compose.runOnUiThread { tree.addOnDrawListener(listener) }
            writeManifest("starting")
            compose.setContent { if (mounted) LocalicaTheme {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                val view = LocalView.current
                val ordinaryHost = requireNotNull(LocalGlassDialogs.current)
                SideEffect { backing = source; root = view; host = ordinaryHost }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalGlassAppearance provides appearance,
                    LocalSessionSelection provides selection,
                    LocalBrowseBackdropObserver provides { value, present ->
                        if (present) scene = value else if (scene === value) scene = null
                    }) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFF8099AA)) }
                        // Same ordinary player clipping wrapper as the existing real QueueSaveOrigin fixture.
                        PlayerClippedOverlay({ 1f }, 80.dp) {
                            QueueSurface(progress, InterfaceSettings(), { settledCallbacks++ }) {
                                val currentMaterial = requireNotNull(LocalFrostedBackdrop.current)
                                val outsideHeader = LocalGlassAppearance.current
                                SideEffect { material = currentMaterial; ambient = outsideHeader }
                                Box(Modifier.weight(1f)) {
                                    CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) {
                                        QueueScreen(state, null, library, onMinimize = { minimizeCallbacks++ },
                                            playerOpen = false, headerShadowStrengthOverride = strength)
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            contentInstalled = true
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${queue.entries.first().id}").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("media-${queue.entries.first().id}").assertIsDisplayed()
            awaitDraw(after = 0, expected = appearance.shadowStrength)
            val ordinary = geometry()
            first = capture("ordinary-header", ordinary)
            val previousDraw = requireNotNull(drawn.get()).count
            compose.runOnIdle { strength = 0f }
            awaitDraw(after = previousDraw, expected = 0f)
            val noHeaderShadow = geometry()
            assertSameOwnersAndGeometry(ordinary, noHeaderShadow)
            second = capture("header-only-zero", noHeaderShadow)
            // Both complete originals have already been saved and hashed before reading any pixels.
            writePairedBands(requireNotNull(first), requireNotNull(second), ordinary)
            assertEquals("Exactly two copies; no replacement capture", 2, copies)
            writeManifest("paired-diagnostic-recorded-NOT-seam-acceptance")
        } catch (failure: Throwable) {
            primary = failure
            runCatching { writeManifest("failed", failure.toString()) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            val bodyFailed = primary != null
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary!!.addSuppressed(failure)
            } }
            clean { if (contentInstalled) { compose.runOnIdle { mounted = false }; compose.waitForIdle() } }
            clean { compose.runOnUiThread {
                if (tree.isAlive) tree.removeOnDrawListener(listener)
                else compose.activity.window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
            } }
            clean { first?.recycle() }; clean { second?.recycle() }
            preferences.forEach { (store, values) -> clean {
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
                assertTrue(edit.commit())
            } }
            if (!bodyFailed && primary != null) {
                runCatching { writeManifest("cleanup-failed", primary.toString()) }.onFailure(primary!!::addSuppressed)
                throw primary!!
            }
        }
    }

    private fun awaitDraw(after: Int, expected: Float) {
        compose.waitForIdle()
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("queue-surface"))
        compose.waitUntil(5000) { compose.runOnUiThread {
            val page = scene
            val draw = drawn.get()
            root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() && !root.isLayoutRequested &&
                draw != null && draw.count > after && draw.requestedStrength == expected &&
                backing.recorded && material.recorded && page?.source?.recorded == true &&
                page.layers.size == 1 && page.layers.single().source.recorded &&
                // Existing producer admission only; never sample a pixel or request a new readback.
                (Build.VERSION.SDK_INT >= 33 || backing.fallback != null && material.snapshot != null && page.source.snapshot != null)
        } }
        compose.runOnIdle {
            assertEquals("Drawer remains at its open endpoint", 1f, progress.value, 0f)
            assertFalse(progress.isRunning)
            assertEquals("No gesture/click changed drawer ownership", 0, settledCallbacks)
            assertEquals(0, minimizeCallbacks)
            assertTrue(host.entries.isEmpty())
            assertTrue(selection.entries.isEmpty())
            assertEquals("Ambient material settings outside header remain identical", appearance, ambient)
            assertSame(backing, material.parent)
            assertSame(material, requireNotNull(scene).source.parent)
        }
    }

    private data class Owner(val semanticsId: Int, val layout: LayoutInfo, val view: View,
        val click: Boolean, val longClick: Boolean, val scroll: Boolean, val disabled: Boolean)
    private data class Geometry(val bounds: Map<String, Rect>, val owners: Map<String, Owner>, val header: LayoutInfo,
        val rootOriginInWindow: Offset, val viewport: BrowseBackdropLayer, val page: BrowseBackdropScene,
        val backing: FrostedBackdrop, val material: FrostedBackdrop, val windowToken: Any?, val density: Float)

    private fun geometry(): Geometry {
        val nodes = linkedMapOf(
            "minimize" to compose.onNodeWithContentDescription("Minimize queue").assertIsDisplayed().fetchSemanticsNode(),
            "selector" to compose.onNodeWithContentDescription("Select queue").assertIsDisplayed().fetchSemanticsNode(),
            "more" to compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed().fetchSemanticsNode(),
            "sort" to compose.onNodeWithContentDescription("Sort queue").assertIsDisplayed().fetchSemanticsNode(),
            "save" to compose.onNodeWithContentDescription("Save queue to playlist").assertIsDisplayed().fetchSemanticsNode(),
            "search" to compose.onNodeWithTag("list-search-pill").assertIsDisplayed().fetchSemanticsNode(),
            "list" to compose.onNodeWithTag("browser-list").assertIsDisplayed().fetchSemanticsNode(),
            "drawer" to compose.onNodeWithTag("queue-surface").assertIsDisplayed().fetchSemanticsNode())
        return compose.runOnUiThread {
            fun ancestors(info: LayoutInfo) = generateSequence(info) { it.parentInfo }.toList()
            val moreAncestors = ancestors(nodes.getValue("more").layoutInfo)
            val header = ancestors(nodes.getValue("sort").layoutInfo).first { candidate -> moreAncestors.any { it === candidate } }
            // Derive the real header ancestor across BOTH control rows; never guess its y/height.
            assertFalse("Search must remain outside the header composition/layout owner",
                ancestors(nodes.getValue("search").layoutInfo).any { it === header })
            val owners = nodes.mapValues { (_, node) -> Owner(node.id, node.layoutInfo,
                (node.root as ViewRootForTest).view, node.config.contains(SemanticsActions.OnClick),
                node.config.contains(SemanticsActions.OnLongClick), node.config.contains(SemanticsActions.ScrollBy),
                node.config.contains(SemanticsProperties.Disabled)) }
            owners.values.forEach { assertSame("One original native source root", root, it.view) }
            val page = requireNotNull(scene)
            val viewport = page.layers.single()
            val bounds = nodes.mapValues { (_, node) -> layoutBounds(node.layoutInfo) }.toMutableMap()
            bounds["header-common-ancestor"] = layoutBounds(header)
            bounds["recorded-list-viewport"] = coordinatesBounds(requireNotNull(viewport.source.location.coordinates))
            val boundary = bounds.getValue("recorded-list-viewport").top
            assertTrue("Actual last header controls precede the recorded viewport", max(bounds.getValue("sort").bottom, bounds.getValue("save").bottom) <= boundary)
            assertTrue("Actual Search target begins inside the recorded viewport", bounds.getValue("search").top >= boundary)
            val origin = IntArray(2).also(root::getLocationInWindow)
            assertTrue("Window coordinate conversion has no hidden native root transform", root.matrix.isIdentity)
            Geometry(bounds, owners, header, Offset(origin[0].toFloat(), origin[1].toFloat()), viewport, page,
                backing, material, root.windowToken, root.resources.displayMetrics.density)
        }
    }

    private fun layoutBounds(info: LayoutInfo) = coordinatesBounds(info.coordinates)
    private fun coordinatesBounds(coordinates: androidx.compose.ui.layout.LayoutCoordinates): Rect {
        assertTrue(coordinates.isAttached)
        val size = coordinates.size
        val corners = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f),
            Offset(0f, size.height.toFloat()), Offset(size.width.toFloat(), size.height.toFloat())).map(coordinates::localToRoot)
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun assertSameOwnersAndGeometry(a: Geometry, b: Geometry) {
        assertEquals("All actual header/list/Search/drawer bounds stay identical", a.bounds, b.bounds)
        assertEquals(a.rootOriginInWindow, b.rootOriginInWindow)
        assertEquals(a.density, b.density, 0f)
        assertSame(a.header, b.header); assertSame(a.windowToken, b.windowToken)
        assertSame(a.viewport, b.viewport); assertSame(a.page, b.page)
        assertSame(a.backing, b.backing); assertSame(a.material, b.material)
        a.owners.forEach { (name, before) ->
            val after = b.owners.getValue(name)
            assertEquals("$name semantic input owner", before.semanticsId, after.semanticsId)
            assertSame("$name layout/input owner retained", before.layout, after.layout)
            assertSame(before.view, after.view)
            assertEquals(before.click, after.click); assertEquals(before.longClick, after.longClick)
            assertEquals(before.scroll, after.scroll); assertEquals(before.disabled, after.disabled)
        }
        // These public invariants are NOT an assertion about private pointer-node dispatch internals.
    }

    private fun facts(g: Geometry) = compose.runOnUiThread {
        val screen = IntArray(2).also(root::getLocationOnScreen)
        val draw = drawn.get()
        JSONObject().put("uptime", SystemClock.uptimeMillis()).put("nonAtomicWithPixelCopy", true)
            .put("requestedHeaderStrength", strength).put("unchangedAmbientAppearance", ambient.toString())
            .put("rootIdentity", id(root)).put("windowIdentity", id(compose.activity.window)).put("tokenIdentity", id(root.windowToken))
            .put("rootOriginOnScreen", JSONArray(screen.toList())).put("rootOriginInWindow", point(g.rootOriginInWindow))
            .put("windowSize", JSONArray(listOf(compose.activity.window.decorView.width, compose.activity.window.decorView.height)))
            .put("focused", root.hasWindowFocus()).put("density", g.density).put("fontScale", root.resources.configuration.fontScale)
            .put("drawerProgress", progress.value).put("nativeDrawCount", draw?.count ?: -1).put("nativeDrawUptime", draw?.uptime ?: -1)
            .put("boundsInRoot", JSONObject().also { obj -> g.bounds.forEach { (key, value) -> obj.put(key, rect(value)) } })
            .put("owners", JSONObject().also { obj -> g.owners.forEach { (key, owner) -> obj.put(key,
                JSONObject().put("semanticsId", owner.semanticsId).put("layoutIdentity", id(owner.layout)).put("rootIdentity", id(owner.view))
                    .put("click", owner.click).put("longClick", owner.longClick).put("scroll", owner.scroll).put("disabled", owner.disabled)) } })
            .put("backing", sourceFacts(g.backing)).put("headerMaterial", sourceFacts(g.material))
            .put("listSamplingScene", sourceFacts(g.page.source)).put("recordedViewport", sourceFacts(g.viewport.source))
            .put("viewportMasked", g.viewport.masked).put("viewportOuterReceipt", g.viewport.outer)
            .put("viewportMaskReceipt", g.viewport.mask ?: JSONObject.NULL)
            .put("nativeConsumedSourceRevision", "UNKNOWN: existing owner receipts do not identify native consumer pixels")
            .put("privatePointerDispatch", "UNKNOWN: preserved semantic/layout/window owners only")
            .put("renderedShadowRaster", "UNKNOWN: requested setting plus draw observation is not a raster consumption receipt")
    }

    private fun sourceFacts(source: FrostedBackdrop) = JSONObject().put("identity", id(source)).put("parentIdentity", id(source.parent))
        .put("size", JSONArray(listOf(source.size.width, source.size.height))).put("originOnScreen", point(source.origin))
        .put("registrationToScreen", source.location.registrationToScreen()?.toString() ?: "UNKNOWN")
        .put("recorded", source.recorded).put("materialOnly", source.materialOnly).put("revision", source.revision)
        .put("requestedReadback", source.readbackRequest).put("publishedReadback", source.snapshotReadbackRequest)
        .put("snapshotIdentity", id(source.snapshot)).put("fallbackIdentity", id(source.fallback)).put("lensIdentity", id(source.lensSnapshot))
        .put("producerDrawReceipt", source.snapshotDraws?.toString() ?: "UNKNOWN")

    private fun capture(label: String, geometry: Geometry): Bitmap {
        assertEquals(if (copies == 0) "ordinary-header" else "header-only-zero", label)
        assertTrue("Only two fixed requests", copies < 2)
        val record = JSONObject().put("label", label).put("before", facts(geometry)).put("status", "copy-requested")
        records.put(record); copies++; writeManifest("running")
        val image = copyOnce()
        try {
            val file = File(output, "$label-original-window.png")
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            record.put("image", file.name).put("bytes", file.length()).put("sha256", sha256(file))
                .put("width", image.width).put("height", image.height)
            val after = this.geometry()
            assertSameOwnersAndGeometry(geometry, after)
            record.put("after", facts(after)).put("status", "original-retained")
            writeManifest("running")
            return image
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }

    /** Fixed diagnostic band: 32dp above and 96dp below the ACTUAL recorded viewport boundary.
     * Includes the Search target and existing 80dp list fade when they fall in that band.
     * Three measured-width zones keep side-button spill separate from the middle. No thresholds. */
    private fun writePairedBands(a: Bitmap, b: Bitmap, g: Geometry) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val viewport = g.bounds.getValue("recorded-list-viewport").translate(g.rootOriginInWindow)
        val left = ceil(viewport.left).toInt().coerceIn(0, a.width)
        val right = floor(viewport.right).toInt().coerceIn(0, a.width)
        val top = floor(viewport.top - 32f * g.density).toInt().coerceIn(0, a.height)
        val bottom = ceil(viewport.top + 96f * g.density).toInt().coerceIn(0, a.height)
        assertTrue("Actual boundary band has native pixels", left < right && top < bottom)
        File(output, "paired-boundary-bands.csv").bufferedWriter().use { out ->
            out.appendLine("zone,yWindow,dyFromViewport,xStart,xEnd,ordinaryR,ordinaryG,ordinaryB,ordinaryA,headerZeroR,headerZeroG,headerZeroB,headerZeroA,signedLumaDelta,meanAbsoluteRgbDelta,maxAbsoluteChannelDelta")
            for (zone in 0..2) {
                val x0 = left + (right - left) * listOf(0, 1, 3)[zone] / 4
                val x1 = left + (right - left) * listOf(1, 3, 4)[zone] / 4
                require(x1 > x0)
                for (y in top until bottom) {
                    val sums = DoubleArray(8); var absolute = 0.0; var maximum = 0
                    for (x in x0 until x1) {
                        val first = a.getPixel(x, y); val second = b.getPixel(x, y)
                        val before = intArrayOf(PixelColor.red(first), PixelColor.green(first), PixelColor.blue(first), PixelColor.alpha(first))
                        val after = intArrayOf(PixelColor.red(second), PixelColor.green(second), PixelColor.blue(second), PixelColor.alpha(second))
                        for (channel in 0..3) {
                            sums[channel] += before[channel]; sums[channel + 4] += after[channel]
                            if (channel < 3) { val delta = kotlin.math.abs(after[channel] - before[channel]); absolute += delta; maximum = max(maximum, delta) }
                        }
                    }
                    val count = x1 - x0; val means = sums.map { it / count }
                    val luma = .2126 * (means[4] - means[0]) + .7152 * (means[5] - means[1]) + .0722 * (means[6] - means[2])
                    out.appendLine("$zone,$y,${y + .5f - viewport.top},$x0,$x1,${means.joinToString(",")},$luma,${absolute / (count * 3)},$maximum")
                }
            }
        }
    }

    private fun copyOnce(): Bitmap {
        val window = compose.activity.window
        val image = compose.runOnUiThread { Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888) }
        return copyNativeWindowOnce(window, image,
            "Single native window copy timed out; no retry") { result -> "Single native window copy failed: $result; no retry" }
    }

    private fun writeManifest(status: String, failure: String? = null) {
        File(output, "manifest.json").writeText(JSONObject().put("status", status).put("failure", failure ?: JSONObject.NULL)
            .put("api", Build.VERSION.SDK_INT).put("declaredCaptures", 2).put("copyAttempts", copies).put("records", records)
            .put("ordinaryShadowStrength", appearance.shadowStrength).put("ordinaryAppearance", appearance.toString())
            .put("fixture", "Real QueueSurface + QueueScreen + PlayerClippedOverlay, open Animatable endpoint, fixed 18-occurrence queue, ordinary selection/host, controller absent and unchanged; opaque 0xFF8099AA recorded backdrop")
            .put("control", "Both states opt into header-only provider, normal configured strength then 0. Null/default path checked statically, not a third capture. Search/list/outer drawer appearance unchanged.")
            .put("boundary", "Measured viewport recording origin plus independent common ancestor/header control/Search/list layout bounds; no fixed y. Numeric CSV only after BOTH original images are retained.")
            .put("limits", "No optical seam-fix/pass threshold. No physical presentation, native source/raster-consumption or private pointer-dispatch proof. Owner snapshots are non-atomic with PixelCopy. Production source readbacks only; none requested by test.")
            .toString(2))
    }
    private fun id(value: Any?): Any = value?.let(System::identityHashCode) ?: "UNKNOWN"
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun point(value: Offset) = JSONArray(listOf(value.x, value.y))
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
