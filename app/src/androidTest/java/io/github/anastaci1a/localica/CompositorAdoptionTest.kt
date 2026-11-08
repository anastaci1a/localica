package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** U04.01b part A (D25/D26/D49): a GlassShadowScope strip is one ShadowPlane; each glass surface casts through one Engine SurfaceNode,
 * and reordering the casters' zIndex leaves the plane's single field unchanged (the plane draws the union after its content). */
class CompositorAdoptionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalTriggerBindsItsActualCasterAndAWindowLeaseSurvivesItsRemoval() {
        var shown by mutableStateOf(true)
        var grouped by mutableStateOf(true)
        lateinit var anchor: GlassAnchor
        compose.setContent { EngineTheme {
            anchor = rememberGlassAnchor()
            @Composable fun trigger() {
                if (shown) LiquidGlassSurface(Modifier.size(48.dp).glassAnchor(anchor), pressFeedback = false)
            }
            if (grouped) GlassShadowScope(Modifier.size(100.dp)) { trigger() }
            else Box(Modifier.size(100.dp)) { trigger() }
        } }
        for (shared in listOf(true, false)) {
            compose.runOnIdle { grouped = shared; shown = true }
            compose.waitForIdle()
            lateinit var host: ShadowPlane
            lateinit var source: SurfaceNode
            lateinit var release: ()->Unit
            compose.runOnIdle {
                host = requireNotNull(anchor.shadowPlane)
                source = requireNotNull(anchor.surfaceNode) as SurfaceNode
                assertSame("The trigger borrows the registered source, never a proxy node (shared=$shared)", source, host.casters.single())
                release = source.retainWindow()
                shown = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNull("Unmounting revokes the physical home binding", anchor.surfaceNode)
                assertSame("The closing native lifetime keeps the same source node", source, host.casters.single())
                release()
                assertTrue("Final native release removes the source registration", host.casters.isEmpty())
            }
        }
    }

    @Test fun descendantOwnersUseTheHostRegistryAndUnregisterOnRemoval() {
        var shown by mutableStateOf(true)
        var host: ShadowPlane? = null
        var child: SurfaceNode? = null
        compose.setContent { EngineTheme {
            GlassShadowScope(Modifier.size(100.dp)) {
                val plane = requireNotNull(LocalSurfaceShadows.current)
                SideEffect { host = plane }
                if (shown) {
                    val shape = rememberPersistentShape("descendant-shape")
                    val node = rememberSurfaceNode("descendant", shape, SurfaceTarget(Stratum.Floating, 1f, ShadowRole.None))
                    SideEffect { child = node }
                }
            }
        } }
        compose.runOnIdle {
            assertSame("A descendant must use its enclosing host's registry", child, requireNotNull(host).casters.single())
            shown = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Removed descendants leave the same registry", requireNotNull(host).casters.isEmpty()) }
    }

    @Test fun layoutCasterOpacityMatchesTheExistingRendererAtEveryWeight() {
        var opacity by mutableFloatStateOf(.2f)
        compose.setContent { EngineTheme {
            val radius = with(LocalDensity.current) { 12.dp.toPx() }
            val cache = rememberGlassShadow()
            Row {
                Box(Modifier.size(140.dp).background(Color.White).testTag("plane-field")) {
                    val plane = rememberShadowPlane()
                    ShadowPlaneHost(plane, 1f, 1f, Modifier.fillMaxSize()) {
                        Box(Modifier.offset(35.dp, 45.dp).size(60.dp, 35.dp)
                            .glassCaster(plane, "fading-caster", { opacity }) { RoundRect(it, CornerRadius(radius)) })
                    }
                }
                Box(Modifier.size(140.dp).background(Color.White).testTag("reference-field").drawWithContent {
                    drawContent()
                    val left = 35.dp.roundToPx().toFloat(); val top = 45.dp.roundToPx().toFloat()
                    val body = RoundRect(left, top, left + 60.dp.roundToPx(), top + 35.dp.roundToPx(), CornerRadius(radius))
                    with(cache) { draw(listOf(body to opacity), 1f, 1f) }
                })
            }
        } }
        for (weight in listOf(.2f, .5f, 1f, .5f, 0f)) {
            compose.runOnIdle { opacity = weight }
            val actual = compose.onNodeWithTag("plane-field").captureToImage().toPixelMap()
            val expected = compose.onNodeWithTag("reference-field").captureToImage().toPixelMap()
            var worst = 0f
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                val a = actual[x, y]; val b = expected[x, y]
                worst = maxOf(worst, abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))
            }
            assertTrue("Plane field preserves opacity $weight, worst RGBA error $worst", worst <= .008f)
        }
    }

    @Test fun zReorderKeepsOneField() {
        var front by mutableStateOf(0)
        val planes = mutableSetOf<ShadowPlane>()
        compose.setContent { LocalicaTheme { EngineRecord {
            CompositionLocalProvider(LocalGlassAppearance provides io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f)) {
                Box(Modifier.size(260.dp, 140.dp).background(Color.White).testTag("strip")) {
                    GlassShadowScope(Modifier.fillMaxSize()) {
                        LocalSurfaceShadows.current?.let { planes += it }
                        listOf(0, 1).forEach { i -> key(i) {
                            Box(Modifier.offset((40 + 70 * i).dp, 30.dp).zIndex(if (front == i) 1f else 0f).size(110.dp, 48.dp)) {
                                LiquidGlassSurface(Modifier.fillMaxSize()) {}
                            }
                        } }
                    }
                }
            }
        } } }
        fun field() = compose.onNodeWithTag("strip").captureToImage().toPixelMap()
        val before = field()
        fun bodies() = compose.runOnIdle { planes.single().casters.flatMap { it.shape.outline().bodies }.toSet() }
        val drawn = bodies()
        compose.runOnIdle {
            val plane = planes.single()
            assertEquals("One SurfaceNode per glass surface on the strip's one plane", 2, plane.casters.size)
            assertEquals(2, plane.casters.map { it.key }.toSet().size)
            assertTrue(plane.casters.all { it.owns(plane) && it.shape.outline().bodies.size == 1 })
        }
        compose.runOnIdle { front = 1 }
        val after = field()
        assertEquals("The plane draws the same body set in any z order", drawn, bodies())
        val d = before.width / 260f
        var worst = 0f; var darkest = 0f; var at = ""
        // Below both bodies (30..78 dp) only the shared field draws.
        for (y in (82 * d).toInt()..(100 * d).toInt()) for (x in (10 * d).toInt() until (250 * d).toInt()) {
            val a = before[x, y]; val b = after[x, y]
            val e = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
            if (e > worst) { worst = e; at = "($x,$y) ${a.red}->${b.red}" }
            darkest = maxOf(darkest, 1f - a.red)
        }
        assertTrue("The strip casts a visible field: $darkest", darkest > .02f)
        // Same budget as the U12 RGBA oracle (.008). API 26 run 42372701302 measured 2/255; the plane's body set is order-independent, so the residue is rasterization.
        assertTrue("A z reorder must not change the plane's field: $worst at $at", worst <= .008f)
    }
}
