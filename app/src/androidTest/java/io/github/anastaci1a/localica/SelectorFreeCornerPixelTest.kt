package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native output must include the area exposed by the tighter free ellipse. */
class SelectorFreeCornerPixelTest {
    @get:Rule val compose = createComposeRule()
    private data class Model(val vertical: Boolean = false, val rtl: Boolean = false, val end: Float = -1f)
    private val scale = 3f
    private fun frame(model: Model): SelectorTrackLens {
        val g = selectorTrackGeometry(192f * scale, 64f * scale, .04f, model.end,
            model.vertical, model.rtl, false, false, maximumExtension = 4f * scale, heldCrossInset = 4f * scale)
        val lens = g.lens(if (model.end < 0f) 0f else 2f, 3, SelectorTrackInsets(0f, 4f * scale, 4f * scale),
            model.vertical, model.rtl, GlassShape(-.2f), g.crossExtent / 2f)
        return lens.copy(bounds = lens.bounds.translate(Offset(16f * scale, 16f * scale)))
    }

    @Test fun sharedSurfacePaintsFreeCornerPixelsAcrossAxesEndsAndRtl() {
        var model by mutableStateOf(Model())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(scale),
                LocalLayoutDirection provides if (model.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalGlassAppearance provides LocalGlassAppearance.current.copy(contrast = 0f, shadowStrength = 0f)) {
                val motion = remember { Animatable(0f) }
                val body = remember { mutableStateOf(GlassShape()) }
                val current = frame(model)
                Box(Modifier.size(224.dp).background(Color.Black), contentAlignment = AbsoluteAlignment.TopLeft) {
                    SelectionLens(Modifier.testTag("corner-lens"), motion, source = null,
                        vertical = model.vertical, tint = Color.White, placement = { current }, deformation = body)
                }
            }
        } }
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true)) for (end in listOf(-1f, 1f)) {
            compose.runOnIdle { model = Model(vertical, rtl, end) }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("corner-lens").captureToImage().toPixelMap()
            val current = frame(model)
            verifyFreePixels(pixels, current.contour().translated(-current.bounds.topLeft), current.corner) {
                it.red > .8f && it.green > .8f && it.blue > .8f
            }
        }
    }


    private fun verifyFreePixels(pixels: PixelMap, contour: SelectorTrackContour, corner: Float,
        painted: (Color) -> Boolean) {
        val previous = SelectorTrackContour.uniform(contour.bounds, corner)
        val margin = listOf(-.6f, 0f, .6f)
        var checked = 0
        val bounds = contour.bounds
        val top = kotlin.math.floor(bounds.top).toInt().coerceAtLeast(0)
        val left = kotlin.math.floor(bounds.left).toInt().coerceAtLeast(0)
        val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceAtMost(pixels.height)
        val right = kotlin.math.ceil(bounds.right).toInt().coerceAtMost(pixels.width)
        for (y in top until bottom) for (x in left until right) {
            val p = Offset(x + .5f, y + .5f)
            if (!contour.bounds.contains(p)) continue
            if (margin.all { dx -> margin.all { dy ->
                    contour.contains(p + Offset(dx, dy)) && !previous.contains(p + Offset(dx, dy))
                } }) {
                assertTrue("The actual surface must paint its new free-corner interior at $p", painted(pixels[x, y]))
                checked++
            }
        }
        assertTrue("A measurable new free-corner region is required", checked > 0)
    }
}
