package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class FrostedCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initiallyClearDescendantsStillSampleIndependentPanelFrost() = verifyCapture(0f)

    @Test fun turningOffGlassBlurDoesNotFreezeDescendantMaterial() = verifyCapture(1f)

    private fun verifyCapture(initialBlur: Float) {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        var blur by mutableFloatStateOf(initialBlur)
        var color by mutableStateOf(Color.Red)
        lateinit var material: FrostedBackdrop
        lateinit var parent: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = blur, whiteTint = 0f)) {
                val source = rememberFrostedBackdrop(color, dark = true)
                parent = source
                Box(Modifier.size(240.dp).testTag("frosted-capture-host")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        val cell = 4.dp.toPx()
                        for (x in 0..(size.width / cell).toInt()) {
                            drawRect(if (x % 2 == 0) color else Color.Black,
                                Offset(x * cell, 0f), Size(cell, size.height))
                        }
                    }
                    FrostedSurface(Modifier.fillMaxSize(), source = source, shadow = false) {
                        material = LocalFrostedBackdrop.current!!
                        LiquidGlassSurface(Modifier.padding(32.dp).fillMaxWidth().height(64.dp)) {}
                    }
                }
            }
        } }
        fun dominant(red: Boolean): Boolean {
            val pixels = material.snapshot?.toPixelMap() ?: return false
            val c = pixels[pixels.width / 2, pixels.height / 2]
            return if (red) c.red > .18f && c.blue < .15f else c.blue > .18f && c.red < .15f
        }
        compose.waitUntil(10000) { material.lensSnapshot != null && dominant(true) }
        compose.runOnIdle { blur = 0f }
        compose.waitUntil(10000) { material.glassFallback == null && material.lensSnapshot === material.snapshot }
        val previous = material.snapshot
        val pixels = previous!!.toPixelMap()
        val row = (pixels.width / 4 until pixels.width * 3 / 4).map { pixels[it, pixels.height / 2].red }
        assertTrue("Panel frost must remain scattered at zero glass blur", row.max() - row.min() < .15f)
        compose.runOnIdle { color = Color.Blue }
        try {
            compose.waitUntil(10000) { material.snapshot !== previous && dominant(false) &&
                material.lensSnapshot === material.snapshot }
        } catch (failure: Throwable) {
            fun sample(image: androidx.compose.ui.graphics.ImageBitmap?): String = image?.toPixelMap()?.let {
                it[it.width / 2, it.height / 2].toString()
            } ?: "missing"
            val displayed = compose.onNodeWithTag("frosted-capture-host").captureToImage().toPixelMap().let {
                it[it.width / 2, it.height * 3 / 4]
            }
            throw AssertionError("Displayed=$displayed, Parent=${sample(parent.snapshot)}, frost=${sample(parent.fallback)}, " +
                "panel=${sample(material.snapshot)}, lens=${sample(material.lensSnapshot)}, " +
                "reused=${material.snapshot === previous}, leases=${parent.consumers}/${material.consumers}", failure)
        }
        assertNull("Clear descendant optics must not require extra scattering", material.glassFallback)
        val displayed = compose.onNodeWithTag("frosted-capture-host").captureToImage().toPixelMap().let {
            it[it.width / 2, it.height * 3 / 4]
        }
        assertTrue("The displayed panel must update alongside its sampled material: $displayed",
            displayed.blue > .18f && displayed.red < .15f)
    }
}
