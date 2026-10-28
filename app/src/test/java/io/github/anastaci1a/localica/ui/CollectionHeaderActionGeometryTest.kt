package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CollectionHeaderActionGeometryTest {
    @Test fun fractionalForwardAndReverseUseOneRasterMappingAndRejectStalePlacement() {
        val source = Rect(0f, -217.35f, 411f, 502.65f)
        val action = Rect(173.5f, 580f, 237.5f, 644f)
        val points = listOf(0f, .13f, .71f, 1f, .71f, .13f, 0f)
        val frames = points.map { progress ->
            val presented = Rect(0f, source.top - 133.7f * progress, 411f, source.bottom - 133.7f * progress)
            collectionHeaderActionRect(source, presented, action).also { actual ->
                assertEquals(actual.left.toInt().toFloat(), actual.left, 0f)
                assertEquals(actual.top.toInt().toFloat(), actual.top, 0f)
                assertEquals(64f, actual.width, 0f)
                assertEquals(64f, actual.height, 0f)
                assertTrue(collectionHeaderActionPlacementMatches(actual, actual))
                assertTrue(collectionHeaderActionPlacementMatches(actual.translate(.001f, -.001f), actual))
                assertFalse(collectionHeaderActionPlacementMatches(actual.translate(0f, 1f), actual))
                assertFalse(collectionHeaderActionPlacementMatches(null, actual))
            }
        }
        assertEquals(frames[0], frames[6]); assertEquals(frames[1], frames[5]); assertEquals(frames[2], frames[4])
        assertTrue(frames.map { it.top }.distinct().size > 2)
    }
}
