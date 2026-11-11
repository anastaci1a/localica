package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.engine.RetainedGlassShadow
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #624: None replaces only the optical material fill. Shadows use the same GlassSettings
 * values through the same code path in both renderers, including while switching. */
class NoneShadowParityTest {
    @get:Rule val compose = localicaComposeRule()
    private val appearance = GlassAppearance(shadowStrength = .6f, shadowSpread = .7f, liftShadowStrength = 1.5f)

    /** [tolerance] is in 8-bit levels: 0 demands identical pixels. */
    private fun outsideBodies(a: PixelMap, b: PixelMap, bodies: List<Rect>, label: String, tolerance: Int = 0) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var differing = 0; var shadowed = 0; var largest = 0f
        val box = android.graphics.Rect()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (bodies.any { it.inflate(1f).contains(Offset(x + .5f, y + .5f)) }) continue
            val p = a[x, y]; val q = b[x, y]
            if (p.red < .97f) shadowed++
            val delta = maxOf(kotlin.math.abs(p.red - q.red), kotlin.math.abs(p.green - q.green), kotlin.math.abs(p.blue - q.blue))
            if (delta > (tolerance + .5f) / 255) { differing++; largest = maxOf(largest, delta); box.union(x, y) }
        }
        assertTrue("$label: the fixture must actually draw a shadow band ($shadowed px)", shadowed > 200)
        assertEquals("$label: shadow pixels differ between Normal and None (largest ${largest * 255}/255 within $box)", 0, differing)
    }

    @Test fun floatingPillAndPanelShadowsMatchAcrossAContinuousSwitch() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides runtime, LocalGlassAppearance provides appearance) { LocalicaTheme {
            val page = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(320.dp, 360.dp).testTag("stage")) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides page) {
                    LiquidGlassSurface(Modifier.offset(60.dp, 50.dp).size(200.dp, 64.dp), cornerRadius = 32.dp, pressFeedback = false)
                    FrostedSurface(Modifier.offset(60.dp, 180.dp).size(200.dp, 120.dp)) {}
                }
            }
        } } }
        val bodies = listOf(Rect(60f, 50f, 260f, 114f), Rect(60f, 180f, 260f, 300f))
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        val settledNormal = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = false
        try {
            // The frame-by-frame reference is taken under the same paused clock as the switch frames.
            compose.mainClock.advanceTimeByFrame()
            val normal = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
            compose.runOnIdle { runtime.request(GlassRendererMode.SingleToneBlur) }
            // Every frame of the blend: no shadow blip while the fill crossfades.
            repeat(24) { frame ->
                compose.mainClock.advanceTimeByFrame()
                outsideBodies(normal, compose.onNodeWithTag("stage").captureToImage().toPixelMap(), bodies, "switch frame $frame")
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        // Between two settled captures the retained shadow raster replaces the live rings; their
        // rounding differs by up to 2 levels whatever the renderer (identical on API 26, where both
        // requests resolve to None). Every frame of the switch itself is compared exactly above.
        outsideBodies(settledNormal, compose.onNodeWithTag("stage").captureToImage().toPixelMap(), bodies, "settled None", 2)
    }

    @Test fun liftAndGlassShadowConfigurationIsIndependentOfTheRenderer() {
        val seen = mutableMapOf<GlassRendererMode, LiftShadow>()
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var lift: LiftShadow? = null
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime,
            LocalGlassAppearance provides appearance) { LocalicaTheme { lift = rememberLiftShadow() } } }
        for (mode in listOf(GlassRendererMode.Normal, GlassRendererMode.SingleToneBlur)) {
            compose.runOnIdle { runtime.request(mode) }
            compose.waitForIdle()
            seen[mode] = requireNotNull(lift)
        }
        assertEquals(seen[GlassRendererMode.Normal], seen[GlassRendererMode.SingleToneBlur])
        assertEquals(appearance.shadowStrength * appearance.liftShadowStrength, seen.getValue(GlassRendererMode.SingleToneBlur).strength, 1e-6f)
        assertEquals(appearance.shadowSpread, seen.getValue(GlassRendererMode.SingleToneBlur).spread, 1e-6f)
    }
    /** #852: the settled comparison must hold the same shadow branch in both captures. A
     * [RetainedGlassShadow] draws live rings until its raster is prepared off Main (after a
     * delay on the composition clock); the two branches round up to 2 levels apart whatever the
     * renderer. Observe production owners only: no raster, key or geometry is changed. */
    private class PreparedShadows {
        private lateinit var root: CompositionData
        private var tables: Set<CompositionData>? = null
        private val requested = RetainedGlassShadow::class.java.getDeclaredField("requested").apply { isAccessible = true }
        private val raster = RetainedGlassShadow::class.java.getDeclaredField("raster\$delegate").apply { isAccessible = true }
        private val owners = mutableListOf<RetainedGlassShadow>()
        fun bind(root: CompositionData, tables: Set<CompositionData>?) { this.root = root; this.tables = tables }
        /** Owners that drew a field in the current composition. */
        private fun drawn(): List<RetainedGlassShadow> {
            owners.clear()
            fun visit(table: CompositionData) {
                for (group in table.compositionGroups) {
                    for (value in group.data) if (value is RetainedGlassShadow && owners.none { it === value }) owners += value
                    visit(group)
                }
            }
            visit(root)
            tables?.toList()?.filter { it !== root }?.forEach(::visit)
            return owners.filter { requested.get(it) != null }
        }
        private fun prepared(owner: RetainedGlassShadow): Boolean {
            val image = (raster.get(owner) as State<*>).value ?: return false
            val key = image.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(image)
            return owner.rasterizations > 0 && requested.get(owner) == key
        }
        fun ready() = drawn().let { it.isNotEmpty() && it.all(::prepared) }
        fun receipt() = drawn().joinToString { "${System.identityHashCode(it)}:${it.rasterizations}:${prepared(it)}" }
    }

    @Test fun queueDrawerEdgeShadowMatchesInNoneAndNormal() {
        val tracks = (1..12).map { Track("shadow:$it", "Queue song $it", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Queue shadow")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        val shadows = PreparedShadows()
        compose.setContent {
            val tables = remember { mutableSetOf<CompositionData>() }
            shadows.bind(currentComposer.compositionData, tables)
            CompositionLocalProvider(LocalInspectionTables provides tables, LocalGlassRendererRuntime provides runtime,
            LocalGlassAppearance provides appearance) { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalSessionSelection provides remember { SessionSelection() }) {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("screen")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                    QueueSurface(remember { Animatable(.45f) }, InterfaceSettings(), {}) {
                        Box(Modifier.weight(1f)) { QueueScreen(state, null, library, onMinimize = {}, playerOpen = false) }
                    }
                }
            }
        } } }
        // Advance the composition clock until every drawn shadow shows its prepared raster for
        // its current key, so both captures are the retained branch (the production settled state).
        fun settled(): PixelMap {
            compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!compose.runOnIdle { shadows.ready() }) {
                assertTrue("Queue drawer shadow raster never prepared (${compose.runOnIdle { shadows.receipt() }})", System.nanoTime() < deadline)
                compose.mainClock.advanceTimeBy(64); Thread.sleep(16); compose.waitForIdle()
            }
            val before = compose.runOnIdle { shadows.receipt() }
            val image = compose.onNodeWithTag("screen").captureToImage().toPixelMap()
            assertEquals("A capture cannot cross a shadow branch or key", before, compose.runOnIdle { shadows.receipt() })
            return image
        }
        val normal = settled()
        val top = compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { runtime.request(GlassRendererMode.SingleToneBlur) }
        val none = settled()
        assertEquals("The drawer edge must not move with the renderer", top,
            compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot.top)
        // The band above the drawer edge holds only its shadow over the static scene.
        val bandHeight = 40
        val y0 = (top.toInt() - bandHeight).coerceAtLeast(0)
        var differing = 0; var shadowed = 0
        for (y in y0 until top.toInt().coerceAtMost(normal.height)) for (x in 0 until normal.width) {
            val p = normal[x, y]; val q = none[x, y]
            if (p.red < .99f) shadowed++
            if (kotlin.math.abs(p.red - q.red) > .5f / 255 || kotlin.math.abs(p.green - q.green) > .5f / 255 ||
                kotlin.math.abs(p.blue - q.blue) > .5f / 255) differing++
        }
        assertTrue("The drawer must cast an edge shadow ($shadowed px)", shadowed > 50)
        assertEquals("Queue drawer edge shadow differs between Normal and None", 0, differing)
    }
}
