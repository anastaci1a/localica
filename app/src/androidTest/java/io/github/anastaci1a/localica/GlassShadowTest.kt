package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GlassShadowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bottomUnionDoesNotKeepAGhostOfAModalTrigger() {
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            val group = remember { GlassShadowGroup() }
            anchor = rememberGlassAnchor()
            CompositionLocalProvider(LocalBottomShadows provides group, LocalGlassAppearance provides
                io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f)) {
                Box(Modifier.size(240.dp, 160.dp).background(Color.White)
                    .bottomGlassShadowHost(group).testTag("shadow-union"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(104.dp, 52.dp).glassAnchor(anchor).bottomGlassShadow())
                }
            }
        } }
        fun darkPixels(): Int {
            val pixels = compose.onNodeWithTag("shadow-union").captureToImage().toPixelMap()
            var result = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                if (pixels[x, y].red < .98f) result++
            return result
        }
        assertTrue(darkPixels() > 100)
        compose.runOnIdle { anchor.modalPresent = true }
        org.junit.Assert.assertEquals("The popup owns its shadow while away from the bottom group", 0, darkPixels())
        compose.runOnIdle { anchor.modalPresent = false }
        assertTrue("The returning trigger rejoins the union", darkPixels() > 100)
    }

    @Test fun retainedShadowMatchesFullGradientAfterShapeAndStrengthChange() {
        var expanded by mutableStateOf(false)
        var strength by mutableFloatStateOf(.8f)
        lateinit var retainedShadow: RetainedGlassShadow
        compose.setContent {
            val scope = rememberCoroutineScope()
            retainedShadow = remember(scope) { RetainedGlassShadow(scope) }
            Column {
                listOf(false, true).forEach { retained ->
                    val geometry = remember { GlassShadowGeometry() }
                    val radius = if (expanded) 12.dp else 22.dp
                    Box(Modifier.size(220.dp, 170.dp).background(Color.White).testTag("shadow-$retained"), Alignment.Center) {
                        Box(Modifier.size(if (expanded) 130.dp else 90.dp, if (expanded) 70.dp else 44.dp)
                            .drawBehind {
                                val bodies = listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())) to 1f)
                                if (retained) with(retainedShadow) { draw(bodies, strength, .7f) }
                                else
                                glassShadows(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())) to 1f),
                                    strength, .7f, geometry)
                            })
                    }
                }
            }
        }
        fun compare(expectedRasterizations: Int) {
            compose.waitUntil(5000) { retainedShadow.rasterizations >= expectedRasterizations }
            val referenceImage = compose.onNodeWithTag("shadow-false").captureToImage()
            val cachedImage = compose.onNodeWithTag("shadow-true").captureToImage()
            val reference = referenceImage.toPixelMap()
            val cached = cachedImage.toPixelMap()
            var error = 0f
            var maximum = 0f
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val delta = abs(reference[x, y].red - cached[x, y].red)
                error += delta
                maximum = maxOf(maximum, delta)
            }
            if (maximum >= .04f) {
                val cacheDir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                listOf("reference" to referenceImage, "cached" to cachedImage).forEach { (name, image) ->
                    java.io.File(cacheDir, "shadow-$name.png").outputStream().use {
                        image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            assertTrue("Cached gradient must preserve opacity and its padded extent: $maximum / $error",
                maximum < .04f && error / (reference.width * reference.height) < .003f)
        }
        compare(1)
        compose.runOnIdle { expanded = true }
        compare(2)
        compose.runOnIdle { strength = .2f }
        compare(3)
    }
}
