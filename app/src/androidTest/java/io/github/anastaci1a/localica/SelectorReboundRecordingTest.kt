package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.*

/** Real navbar placement exercises the circular-lens case; pixels verify padded sample origins. */
class SelectorReboundRecordingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var source: FrostedBackdrop? = null
    private var stage by mutableIntStateOf(0)
    private val masks = mutableMapOf<Float, Triple<IntArray, Int, Float>>()
    private var sourceGrid: Pair<IntArray, Size>? = null

            @Test fun adjustableGlassKeepsItsLandmarkDuringCoupledRebound() = rebound()

    private fun rebound() {
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            val backing = rememberFrostedBackdrop(Unit)
            source = backing

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(240.dp, 112.dp).testTag("rebound-scene")) {
                        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) {
                            drawRect(Color.Blue)
                            // An origin error from the new cross padding moves this narrow
                            // source landmark away from the lens's zero-Y-deflection center.
                            drawRect(Color.Red, Offset(0f, 54.dp.toPx()), Size(size.width, 4.dp.toPx()))
                        }
                        Canvas(Modifier.matchParentSize()) { drawRect(Color.Black) }
                        Box(Modifier.offset(24.dp, 24.dp).size(192.dp, 64.dp)) {
                            val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
                            SlidingTabs(tabs, MainTab.Home, { 0f }, backing) { }
                        }
                        PresentationMarker { stage }
                    }
                }

        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.snapshot != null }
        compose.waitForIdle()
        fun bounds() = compose.onNodeWithTag("tab-selector").fetchSemanticsNode().boundsInRoot
        val rest = bounds()
        val host = compose.onNodeWithTag("tab-track-host")
        val hostRest = host.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            host.performTouchInput { down(Offset(width / 6f, centerY)); held = true; moveBy(Offset(-150f, 0f), 100) }
            compose.mainClock.advanceTimeBy(400)
            landmark(bounds())
            host.performTouchInput { up(); held = false }
            val frames = mutableListOf<Rect>()
            repeat(24) {
                compose.mainClock.advanceTimeBy(32)
                frames += bounds()
                landmark(frames.last())
                assertEquals("Recording clearance never changes resting input bounds", hostRest, host.getUnclippedBoundsInRoot())
            }
            assertTrue("Circular navbar lens must have visible width rebound", frames.maxOf { it.width } - frames.minOf { it.width } > 1f)
            assertTrue("Circular navbar lens must have visible height rebound", frames.maxOf { it.height } - frames.minOf { it.height } > 1f)
            assertTrue("Circular navbar has resolved opposite-phase dimensions", frames.drop(4).any {
                abs(it.width - rest.width) > 1f && abs(it.height - rest.height) > 1f &&
                    (it.width - rest.width) * (it.height - rest.height) < 0f
            })
            compose.mainClock.advanceTimeBy(2400)
            val final = bounds()
            assertEquals(rest.width, final.width, 1f)
            assertEquals(rest.height, final.height, 1f)
            landmark(final)
        } finally {
            try {
                if (held) host.performTouchInput { cancel() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun landmark(lens: Rect) {
        compose.runOnIdle { stage = (stage + 1) % 32 }
        val scene = compose.onNodeWithTag("rebound-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val local = lens.translate(-origin)
        val glow = if (Build.VERSION.SDK_INT < 33) measuredGlow() else GlassGlow()
        val mean = if (Build.VERSION.SDK_INT < 33) independentSourceMean(local) else Color.Transparent
        val expected = if (Build.VERSION.SDK_INT < 33) listOf(.18f, .82f).associateWith { fraction ->
            val x = (local.left + local.width * fraction).roundToInt()
            val y = local.center.y.roundToInt()
            (y - 1..y + 1).map { row -> independentBlue(mean, glow, local, Offset(x + .5f, row + .5f)) }
        } else emptyMap()
        val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
        val y = (lens.center.y - origin.y).roundToInt().coerceIn(1, pixels.height - 2)
        // Sample on either side of the centered glyph, away from its white/accent ink.
        for (fraction in listOf(.18f, .82f)) {
            val x = (lens.left + lens.width * fraction - origin.x).roundToInt().coerceIn(1, pixels.width - 2)
            val colors = (y - 1..y + 1).map { pixels[x, it] }
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue("Padded navbar sampling lost its center landmark at $lens fraction=$fraction: $colors",
                    colors.any { it.red > .6f && it.blue < glassFillChannel(2, 1f, 0f, 0f) + .4f * (glassFillChannel(2, 0f, 0f, 1f) - glassFillChannel(2, 0f, 0f, 0f)) })
            } else {
                val blue = requireNotNull(expected[fraction])
                fun endpoint(actual: Float, reference: Float) = abs(actual - reference) < .01f
                assertTrue("The fixed default blue endpoint includes active glow: mean=$mean glow=$glow expected=$blue actual=$colors",
                    colors.indices.all { endpoint(colors[it].blue, blue[it]) && colors[it].green < .95f && colors[it].red < colors[it].blue })
                assertFalse("A wrong endpoint is rejected at the unchanged .01 budget", endpoint(blue[1], blue[1] + .02f))
                assertTrue("The capability fallback has no spatial landmark inside its flat fill: $colors",
                    colors.maxOf { it.red } - colors.minOf { it.red } < .02f)
            }
        }
    }

    /** Read the existing draw closure's contact, never the candidate's resulting pixels. */
    private fun measuredGlow(): GlassGlow {
        val info = compose.onNodeWithTag("tab-selector").fetchSemanticsNode().layoutInfo
        return compose.runOnUiThread {
        val pending = java.util.ArrayDeque<Pair<Any, Int>>()
        glassLayoutDescendants(info).flatMap { it.getModifierInfo() }.forEach { pending.add(it.modifier to 0) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val contacts = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GlassContact, Boolean>())
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeFirst()
            if (!seen.add(value)) continue
            if (value is GlassContact) { contacts.add(value); continue }
            val type = value.javaClass
            if (depth >= 8 || !(type.name.startsWith("io.github.anastaci1a.localica.ui.") ||
                type.name.startsWith("androidx.compose.ui.draw.") || value is Function<*>)) continue
            type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.type.isPrimitive }.forEach {
                it.isAccessible = true
                it.get(value)?.let { child -> pending.add(child to depth + 1) }
            }
        }
        assertEquals("One contact owns the selector material draw", 1, contacts.size)
        contacts.single().state.glow.value
        }
    }

    /** Independent byte box reduction of the known blue scene and four-dp red stripe. */
    private fun independentSourceMean(bounds: Rect): Color {
        val d = compose.density.density
        val (grid, extent) = sourceGrid ?: run {
            val width = requireNotNull(source).size.width
            val height = requireNotNull(source).size.height
            fun power(n: Int): Int { var p = 1; while (p < n) p *= 2; return p }
            val padded = Size(power(width).toFloat(), power(height).toFloat())
            var w = padded.width.toInt(); var h = padded.height.toInt()
            var raster = IntArray(w*h) { i ->
                val x = i % w; val y = i / w
                if (x >= width || y >= height) 0 else {
                    val red = (min(y + 1f, 58f*d) - max(y.toFloat(), 54f*d)).coerceIn(0f, 1f)
                    (255 shl 24) or ((red*255).roundToInt() shl 16) or ((1f-red)*255).roundToInt()
                }
            }
            while (w > 32 || h > 32) {
                val nextW = max(32, w/2); val nextH = max(32, h/2)
                val nx = w/nextW; val ny = h/nextH
                val next = IntArray(nextW*nextH) { i ->
                    var pixel = 0
                    for (shift in listOf(0, 8, 16, 24)) {
                        var sum = 0
                        for (dy in 0 until ny) for (dx in 0 until nx)
                            sum += raster[(i/nextW*ny+dy)*w+i%nextW*nx+dx] ushr shift and 255
                        pixel = pixel or ((sum.toFloat()/(nx*ny)).roundToInt() shl shift)
                    }
                    pixel
                }
                raster = next; w = nextW; h = nextH
            }
            (raster to padded).also { sourceGrid = it }
        }
        val box = Rect(bounds.left*32/extent.width, bounds.top*32/extent.height,
            bounds.right*32/extent.width, bounds.bottom*32/extent.height)
        var r = 0f; var b = 0f; var alpha = 0f
        for (y in floor(box.top).toInt().coerceAtLeast(0) until ceil(box.bottom).toInt().coerceAtMost(32))
            for (x in floor(box.left).toInt().coerceAtLeast(0) until ceil(box.right).toInt().coerceAtMost(32)) {
                val area = (min(box.right, x+1f)-max(box.left, x.toFloat())) * (min(box.bottom, y+1f)-max(box.top, y.toFloat()))
                val pixel = grid[y*32+x]
                val a = (pixel ushr 24)/255f*area
                alpha += a
                r += (pixel ushr 16 and 255)/255f*area
                b += (pixel and 255)/255f*area
            }
        return Color(r/alpha, 0f, b/alpha)
    }

    private fun independentBlue(mean: Color, glow: GlassGlow, bounds: Rect, point: Offset): Float {
        val rgb = floatArrayOf(mean.red, mean.green, mean.blue)
        val luma = .2126f*rgb[0] + .7152f*rgb[1] + .0722f*rgb[2]
        val lift = 1f-(1f-luma)*.94f/(1f-.06f*luma)
        val tint = ((lift-luma)/(1f-luma)).coerceIn(0f, 1f)
        fun byte(v: Float) = (v.coerceIn(0f, 1f)*255).roundToInt()/255f
        var incoming = rgb.map { val c = it + (1f-it)*tint; byte(c-.25f*c*c) }
        val broad = arrayOf(floatArrayOf(1.1575f, -.1431f, -.0144f), floatArrayOf(-.0425f, 1.0569999f, -.0145f), floatArrayOf(-.0426f, -.143f, 1.1855999f))
        val local = arrayOf(floatArrayOf(1.3937f, -.3577f, -.036f), floatArrayOf(-.1063f, 1.1424f, -.0361f), floatArrayOf(-.1064f, -.3575f, 1.4639f))
        fun apply(rows: Array<FloatArray>, gain: Float, bias: Float, alpha: Float) {
            val output = rows.map { row -> byte(row.indices.sumOf { (row[it]*gain*incoming[it]).toDouble() }.toFloat()+bias) }
            incoming = incoming.indices.map { byte(incoming[it]*(1f-alpha)+output[it]*alpha) }
        }
        apply(broad, 1f, .05f, byte(glow.big))
        val p = mean.luminance().toDouble().pow(1.25)
        val white = java.math.BigDecimal(1-p).multiply(java.math.BigDecimal(.9)).add(java.math.BigDecimal(p*.25)).toDouble().toFloat()
        val gain = round((1f/(1f-white.coerceAtMost(255f/256f)))/.0001f)*.0001f
        val radius = min(.75f*min(bounds.width, bounds.height), 80f*compose.density.density)
        val (disk, side, pixels) = masks.getOrPut(radius) { touchLightReferenceMask(radius) }
        for (frame in glow.retiring + glow) {
            if (frame.little <= 0f) continue
            val scale = pixels*frame.scale
            val x = (point.x-bounds.left-bounds.width*frame.point.x)/scale+side/2f-.5f
            val y = (point.y-bounds.top-bounds.height*frame.point.y)/scale+side/2f-.5f
            val ix = floor(x).toInt(); val iy = floor(y).toInt()
            fun value(dx: Int, dy: Int) = if (ix+dx in 0 until side && iy+dy in 0 until side) disk[(iy+dy)*side+ix+dx]/255f else 0f
            val fx = x-ix; val fy = y-iy
            val coverage = (value(0,0)*(1-fx)+value(1,0)*fx)*(1-fy)+(value(0,1)*(1-fx)+value(1,1)*fx)*fy
            val cleaned = local.map { row -> row.map { v -> sign(v)*floor(abs(v*gain)/.0001f+.5f)*.0001f }.toFloatArray() }.toTypedArray()
            apply(cleaned, 1f, 0f, byte(frame.little)*coverage)
        }
        return incoming[2]
    }
}
