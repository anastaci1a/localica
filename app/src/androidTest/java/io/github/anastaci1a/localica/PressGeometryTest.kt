package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.expm1
import kotlin.math.roundToInt

/** #1080/#1075: every pressable glass class, wired the way its screen wires it, draws one press geometry. */
class PressGeometryTest {
    @get:Rule val compose = localicaComposeRule()
    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also {
        image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }
    private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255

    /** Width, height (dp), popup trigger (anchored like Sort/View, menus and the search bar), action-button override. */
    private data class Element(val name: String, val w: Float, val h: Float, val trigger: Boolean = false, val action: Boolean = false)
    private val elements = listOf(
        Element("circle button", 51.2f, 51.2f), Element("menu circle button", 51.2f, 51.2f, trigger = true),
        Element("album play button", 64f, 64f), Element("Sort/View pill", 110f, 51.2f, trigger = true),
        Element("split pill", 144f, 64f), Element("action button pill", 160f, 51.2f, action = true),
        Element("wide bar", 328f, 64f, trigger = true), Element("scoped search bar", 328f, 40f, trigger = true))

    @Test fun everyElementClassFollowsTheSizeLawStaysInTheWindowAndStretchesTowardTheDrag() {
        var index by mutableIntStateOf(0)
        val contacts = elements.map { if (it.trigger || it.action) null else GlassContact() }
        val anchors = elements.indices.map { i -> if (elements[i].trigger) GlassAnchor() else null }
        compose.setContent { EngineTheme(clear = true) { MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(360.dp, 200.dp).testTag("window").drawBehind { drawRect(Color.Black) }, contentAlignment = Alignment.Center) {
                    val e = elements[index]
                    key(index) {
                        LiquidGlassSurface(Modifier.size(e.w.dp, e.h.dp).testTag("body").glassAnchor(anchors[index]), source = null, tint = Color.White,
                            cornerRadius = (minOf(e.w, e.h) / 2).dp, shadow = false, sharedContact = contacts[index],
                            bodyOverride = if (e.action) FlexSpec.Small else null)
                    }
                }
        } } }
        val window = compose.onNodeWithTag("window")
        val density = compose.density.density
        compose.mainClock.autoAdvance = false
        fun span(row: Int): Pair<Int, Int> {
            val image = window.captureToImage(); val all = pixels(image)
            val line = IntArray(image.width) { x -> listOf(0, 8, 16).maxOf { channel(all[row * image.width + x], it) } }
            val half = line.max() / 2
            return line.indexOfFirst { it > half } to line.indexOfLast { it > half }
        }
        val windowWidth = (360 * density).roundToInt()
        val mode = "default"
        for ((i, e) in elements.withIndex()) {
            compose.runOnIdle { index = i }
            compose.mainClock.advanceTimeBy(1500)
            val body = compose.onNodeWithTag("body")
            val row = (body.fetchSemanticsNode().boundsInRoot.center.y - window.fetchSemanticsNode().boundsInRoot.top).roundToInt()
            val momentum = (anchors[i]?.contact ?: contacts[i])?.state?.momentum
            val rest = span(row)
            body.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2500)
            val held = span(row)
            val o = compose.runOnIdle { momentum?.origin?.x?.toDouble() } ?: .5
            body.performTouchInput { moveBy(Offset(100 * density, 0f)) }
            compose.mainClock.advanceTimeBy(2500)
            val dragged = span(row)
            body.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(10000)
            compose.runOnIdle { momentum?.let { assertTrue("${e.name} settles after release mode=$mode", it.settled) } }
            assertEquals("${e.name} rendered span returns after release mode=$mode", rest, span(row))
            val long = maxOf(e.w, e.h); val short = minOf(e.w, e.h)
            val p = if (long < 120) 16f else 16f - 12f * ((short - 44f) / 116f).coerceIn(0f, 1f)
            val growth = (held.second - held.first) - (rest.second - rest.first)
            val near = dragged.second - held.second; val far = dragged.first - held.first
            android.util.Log.i("Localica1080", "mode=$mode ${e.name} ${e.w}x${e.h} density=$density rest=$rest held=$held dragged=$dragged growth=$growth expected=${p * density * e.w / long} near=$near far=$far")
            assertEquals("${e.name} rest layout mode=$mode", e.w * density, (rest.second - rest.first + 1).toFloat(), 1.5f)
            assertEquals("${e.name} width grows by P·W/L mode=$mode", p * density * e.w / long, growth.toFloat(), 1.5f)
            assertEquals("${e.name} held body stays centred mode=$mode", (rest.first + rest.second) / 2f, (held.first + held.second) / 2f, 1f)
            for ((label, s) in listOf("held" to held, "dragged" to dragged))
                assertTrue("${e.name} $label stays inside the window mode=$mode span=$s", s.first > 0 && s.second < windowWidth - 1)
            // The drag law's own edge prediction, as SplitPillContactTest pins it: pixel rounding of a 1.97/.57 px split pill at density 1.75 cannot keep a 2 px gap.
            val d = if (long < 120) 2000.0 else 6000 + 18000 * ((short - 44.0) / 116).coerceIn(0.0, 1.0)
            val travel = 100 / e.w.toDouble(); val u = minOf(travel, 1 - o); val f = e.w / d * (u / 4 + travel - u)
            val stretch = (1 + p / long) * e.w * expm1(f) / 2
            assertEquals("${e.name} near end follows the drag law mode=$mode", (f * e.w + stretch) * density, near.toDouble(), 1.5)
            assertEquals("${e.name} far end follows the drag law mode=$mode", (f * e.w - stretch) * density, far.toDouble(), 1.5)
            assertTrue("${e.name} near end moves farther than the held far end mode=$mode near=$near far=$far", near > far)
        }
    }
}
