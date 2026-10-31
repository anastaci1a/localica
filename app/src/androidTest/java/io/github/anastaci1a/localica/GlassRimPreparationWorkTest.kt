package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test

class GlassRimPreparationWorkTest {
    private class Work {
        var brushPairs = 0
        var roundedPaths = 0
        val observer: (GlassRimPreparation) -> Unit = { kind ->
            when (kind) {
                GlassRimPreparation.BrushPair -> brushPairs++
                GlassRimPreparation.RoundedPath -> roundedPaths++
            }
        }
    }

    @Test fun repeatedDrawsReportPreparationAndMatchFrozenPixelsAcrossInvalidation() {
        val candidate = Work()
        val original = Work()
        var cache = GlassRimCache(candidate.observer)
        val initialBounds = Rect(5f, 7f, 88f, 70f)
        var bounds = initialBounds
        var radius = 11f
        var tone = GlassTone()
        var density = 1f
        var opacity = 1f
        var supplied: Path? = null
        var draws = 0

        fun render(reference: Boolean): IntArray {
            val image = ImageBitmap(96, 80)
            try {
                CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(96f, 80f)) {
                    if (reference) drawOriginalGlassRim(bounds, radius, supplied, tone, opacity, original.observer)
                    else drawGlassRim(bounds, radius, supplied, tone, opacity, cache)
                }
                return IntArray(96 * 80).also {
                    image.asAndroidBitmap().getPixels(it, 0, 96, 0, 0, 96, 80)
                }
            } finally { image.asAndroidBitmap().recycle() }
        }

        fun stage(name: String, pairs: Int, paths: Int, originalPaths: Int, repeats: Int = 1) {
            repeat(repeats) {
                assertArrayEquals("Exact frozen pixels at $name/$it", render(true), render(false))
                draws++
            }
            assertEquals("Candidate brush pairs at $name", pairs, candidate.brushPairs)
            assertEquals("Candidate rounded paths at $name", paths, candidate.roundedPaths)
            assertEquals("Original brush pairs at $name", draws, original.brushPairs)
            assertEquals("Original rounded paths at $name", originalPaths, original.roundedPaths)
            Log.i("GlassRimPreparationWork", "stage=$name draws=$draws candidateBrushPairs=${candidate.brushPairs} " +
                "candidateRoundedPaths=${candidate.roundedPaths} originalBrushPairs=${original.brushPairs} " +
                "originalRoundedPaths=${original.roundedPaths}")
        }

        stage("stable", 1, 1, 32, repeats = 32)
        density = 2f
        stage("density", 1, 1, 33)
        opacity = .35f
        stage("opacity", 1, 1, 34)
        tone = GlassTone(.6f, 1.4f, true)
        stage("tone", 2, 1, 35)
        radius = 9f
        stage("radius", 2, 2, 36)
        bounds = bounds.translate(Offset(2f, -1f))
        stage("translation", 3, 3, 37)
        val external = Path().apply { moveTo(12f, 8f); lineTo(80f, 20f); lineTo(40f, 68f); close() }
        val externalBounds = external.getBounds()
        supplied = external
        stage("supplied", 3, 3, 37)
        assertEquals(externalBounds, external.getBounds())
        val replacement = Path().apply { addOval(Rect(12f, 10f, 80f, 68f)) }
        supplied = replacement
        val replacementBounds = replacement.getBounds()
        stage("supplied-replacement", 3, 3, 37)
        assertEquals(replacementBounds, replacement.getBounds())
        assertEquals(externalBounds, external.getBounds())
        supplied = null
        stage("return-rounded", 3, 3, 38)
        bounds = Rect(3f, 4f, 78f, 66f)
        stage("extent", 4, 4, 39)
        bounds = initialBounds
        radius = 11f
        tone = GlassTone()
        stage("revisit", 5, 5, 40)
        cache = GlassRimCache(candidate.observer)
        stage("new-owner", 6, 6, 41)
    }
}
