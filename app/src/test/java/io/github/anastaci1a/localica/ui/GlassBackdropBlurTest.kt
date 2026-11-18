package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlassBackdropBlurTest {
    private val fits = listOf(2f to 5.165593576f, 2.625f to 5.755008754f, 3f to 6.062170952f)

    @Test fun defaultRestRadiusMatchesTheRecordedPixelFits() {
        for ((density, recordedRadius) in fits) {
            val restRadius = glassBackdropBlurRadius(density) * GlassAppearance().blur
            assertEquals(recordedRadius, restRadius * .5f, .00001f)
            assertTrue("Default rest remains on the half-resolution endpoint", restRadius >= 6f)
        }
    }

    @Test fun interpolationUsesRecordedPixelsBeforeDisplayConversion() {
        for ((density, recordedRadius) in listOf(2.3125f to 5.460301165f, 2.8125f to 5.908589853f)) {
            val effectiveDp = glassBackdropBlurRadius(density) * GlassAppearance().blur / density
            assertEquals(recordedRadius, effectiveDp * density * .5f, .00001f)
        }
    }

    @Test fun settingsAndFrostKeepClearDefaultAndFullEndpoints() {
        for ((density, fittedRadius) in fits) {
            for ((setting, frost, expectedMultiple) in listOf(
                Triple(0f, 0f, 0f), Triple(.2f, 0f, 1f), Triple(1f, 0f, 5f),
                Triple(0f, 1f, 5f), Triple(.2f, 1f, 5f), Triple(1f, 1f, 5f))) {
                val radius = glassBackdropBlurRadius(density) * (setting + (1f - setting) * frost)
                val scattering = ((radius - 1f) / 5f).coerceIn(0f, 1f)
                val resolution = 1f - .5f * scattering * scattering * (3f - 2f * scattering)
                assertEquals(fittedRadius * expectedMultiple, radius * resolution, .00001f)
            }
        }
    }

    @Test fun densitiesOutsideTheFittedIntervalUseTheNearestRecordedRadius() {
        assertEquals(glassBackdropBlurRadius(2f), glassBackdropBlurRadius(1f), 0f)
        assertEquals(glassBackdropBlurRadius(3f), glassBackdropBlurRadius(4f), 0f)
    }

    @Test fun sharedRendererConsumesTheRadiusWithoutAnotherDensityConversion() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/LiquidGlass.kt").readText()
        assertTrue(source.contains("val blurRadius = glassBackdropBlurRadius(density.density)"))
        assertTrue(source.contains("val blur = blurRadius * (appearance.blur + (1f - appearance.blur) * frost)"))
        assertTrue(source.contains("resolutionReader = presentation?.let { read -> { val b = blurRadius * (appearance.blur + (1f - appearance.blur) * read().frost.coerceIn(0f, 1f))"))
        assertTrue(source.contains("val blur = frame?.let { blurRadius * (appearance.blur + (1f - appearance.blur) * it.frost.coerceIn(0f, 1f)) } ?: blur"))
        val call = source.substringAfter("if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(")
            .substringBefore("else if (blur > 0f)")
        assertTrue(call.contains("\n                    blur * resolution,"))
    }
}
