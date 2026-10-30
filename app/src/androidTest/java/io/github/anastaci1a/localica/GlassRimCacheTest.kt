package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test

class GlassRimCacheTest {
    private fun render(bounds: Rect, radius: Float, path: Path?, tone: GlassTone,
        opacity: Float, density: Float, cache: GlassRimCache?, original: Boolean): IntArray {
        val image = ImageBitmap(96, 80)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(96f, 80f)) {
                drawRect(Color(0xff526475))
                if (original) drawOriginalGlassRim(bounds, radius, path, tone, opacity)
                else drawGlassRim(bounds, radius, path, tone, opacity, cache)
            }
            return IntArray(96 * 80).also { image.asAndroidBitmap().getPixels(it, 0, 96, 0, 0, 96, 80) }
        } finally { image.asAndroidBitmap().recycle() }
    }

    @Test fun cachedRimsMatchOriginalPixelsAcrossGeometryToneDensityAndOpacity() {
        val cache = GlassRimCache()
        val irregular = Path().apply {
            moveTo(8f, 9f); lineTo(70f, 12f); lineTo(82f, 46f)
            lineTo(40f, 65f); lineTo(12f, 45f); close()
        }
        val pathBounds = irregular.getBounds()
        for (bounds in listOf(Rect(5f, 7f, 88f, 70f), Rect(9f, 4f, 74f, 64f), Rect(5f, 7f, 88f, 70f))) {
            for (radius in listOf(0f, 11f, 30f)) for (tone in listOf(GlassTone(), GlassTone(.6f, 1.4f, true))) {
                for (density in listOf(1f, 2.5f)) for (opacity in listOf(0f, .35f, 1f)) {
                    for (path in listOf(null, irregular)) {
                        val expected = render(bounds, radius, path, tone, opacity, density, null, true)
                        repeat(2) {
                            assertArrayEquals(expected, render(bounds, radius, path, tone, opacity, density, cache, false))
                        }
                    }
                }
            }
        }
        assertEquals(pathBounds, irregular.getBounds())
    }

    @Test fun eachOwnerRetainsOnlyItsCurrentPreparationWithoutMutatingOldPaths() {
        val cache = GlassRimCache()
        val bounds = Rect(2f, 4f, 42f, 34f)
        val tone = GlassTone()
        val firstBrushes = cache.brushes(bounds.center, tone)
        assertSame(firstBrushes, cache.brushes(bounds.center, tone))
        assertNotSame(firstBrushes, cache.brushes(bounds.center, GlassTone(.5f)))
        val restored = cache.brushes(bounds.center, tone)
        assertNotSame(firstBrushes, restored)
        assertNotSame(restored, cache.brushes(Offset(40f, 20f), tone))
        val first = cache.roundedPath(bounds, 7f)
        assertSame(first, cache.roundedPath(bounds, 7f))
        assertNotSame(first, cache.roundedPath(bounds, 9f))
        val moved = cache.roundedPath(bounds.translate(Offset(3f, 8f)), 7f)
        assertNotSame(first, moved)
        assertEquals(bounds, first.getBounds())
        assertNotSame(first, cache.roundedPath(bounds, 7f))
        val other = GlassRimCache()
        assertNotSame(cache.roundedPath(bounds, 7f), other.roundedPath(bounds, 7f))
        assertNotSame(cache.brushes(bounds.center, tone), other.brushes(bounds.center, tone))
    }
}
