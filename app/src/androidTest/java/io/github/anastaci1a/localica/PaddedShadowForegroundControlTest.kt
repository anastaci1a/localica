package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Diagnostic controls only: original GlassShadowTest remains unchanged. */
class PaddedShadowForegroundControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clippedForegroundWithoutShadowsMatchesDirectReference() =
        compare(clippedForeground = true, strength = 0f)

    @Test fun matchingForegroundPrimitivesPreserveFullPaddedShadow() =
        compare(clippedForeground = false, strength = .8f)

    private fun compare(clippedForeground: Boolean, strength: Float) {
        var alpha by mutableFloatStateOf(1f)
        var expanded by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column {
                listOf(false, true).forEach { actual ->
                    val geometry = remember { GlassShadowGeometry() }
                    val width = if (expanded) 130.dp else 90.dp
                    val height = if (expanded) 70.dp else 44.dp
                    val radius = 22.dp
                    val x = if (expanded) 9.dp else (-13).dp
                    val y = if (expanded) (-7).dp else 11.dp
                    Box(Modifier.size(220.dp, 170.dp).background(Color.White).testTag("padded-$actual"), Alignment.Center) {
                        if (actual) Box(Modifier.size(width, height)
                            .glassShadowOffset { IntOffset(x.roundToPx(), y.roundToPx()) }.glassShadowOpacity { alpha }
                            .clearGlassShadow(strength, .6f, radius)
                            .then(if (clippedForeground) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)).background(Color.Red)
                                else Modifier.drawBehind {
                                    drawRoundRect(Color.Red, cornerRadius = CornerRadius(radius.toPx()))
                                }))
                        else Box(Modifier.fillMaxSize().drawBehind {
                            val w = width.roundToPx().toFloat(); val h = height.roundToPx().toFloat()
                            val left = ((size.width-w)/2).roundToInt().toFloat() + x.roundToPx() * (if (rtl) -1 else 1)
                            val top = ((size.height-h)/2).roundToInt().toFloat() + y.roundToPx()
                            val rect = RoundRect(left, top, left+w, top+h, CornerRadius(radius.toPx()))
                            // A full-scene reference has no surface-sized clipping layer.
                            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                                androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                            glassShadows(listOf(rect to 1f), strength, .6f, geometry)
                            drawRoundRect(Color.Red, androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                                androidx.compose.ui.geometry.Size(rect.width, rect.height), CornerRadius(radius.toPx()))
                            drawContext.canvas.restore()
                        })
                    }
                }
            }
        } }
        listOf(1f, .5f, 0f, .25f, 1f, .5f, 1f, 0f, 1f).forEachIndexed { index, opacity ->
            compose.runOnIdle { alpha = opacity; expanded = index % 2 == 1; rtl = index % 3 == 1 }
            val expectedImage = compose.onNodeWithTag("padded-false").captureToImage()
            val expected = expectedImage.toPixelMap()
            val actualImage = compose.onNodeWithTag("padded-true").captureToImage()
            val actual = actualImage.toPixelMap()
            var error = 0f
            var max = 0f
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val difference = abs(expected[x,y].red - actual[x,y].red) + abs(expected[x,y].green - actual[x,y].green)
                error += difference
                max = maxOf(max, difference)
            }
            try {
                assertTrue("Padded field and foreground share opacity $opacity on reversal $index: max=$max error=$error",
                    max < .08f && error / (expected.width*expected.height) < .006f)
            } catch (failure: AssertionError) {
                try {
                    val directory = nativeEvidenceDirectory("glass-shadow-failures",
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
                    listOf("reference" to expectedImage, "actual" to actualImage).forEach { (name, image) ->
                        java.io.File(directory, "foreground-$clippedForeground-$index-$name.png").outputStream().use {
                            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    java.io.File(directory, "foreground-$clippedForeground-$index.txt").writeText(
                        "clippedForeground=$clippedForeground strength=$strength opacity=$opacity reversal=$index expanded=$expanded rtl=$rtl " +
                            "width=${expected.width} height=${expected.height} max=$max error=$error\n")
                } catch (diagnosticFailure: Throwable) {
                    if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                }
                throw failure
            }
        }
    }

}
