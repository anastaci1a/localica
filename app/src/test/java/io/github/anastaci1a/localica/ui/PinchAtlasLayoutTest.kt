package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PinchAtlasLayoutTest {
    private fun verify(sizes: List<IntSize>, minimumWidth: Int = 0): PinchAtlasLayout {
        val layout = packPinchAtlas(sizes, minimumWidth)
        assertEquals(sizes.size, layout.origins.size)
        sizes.forEachIndexed { i, size ->
            val at = layout.origins[i]
            assertTrue(at.x >= 1 && at.y >= 1)
            assertTrue(at.x.toLong() + size.width < layout.size.width)
            assertTrue(at.y.toLong() + size.height < layout.size.height)
            for (j in 0 until i) {
                val other = layout.origins[j]
                val previous = sizes[j]
                assertTrue("Every original region keeps a clear gutter",
                    at.x.toLong() >= other.x.toLong() + previous.width + 2 ||
                        other.x.toLong() >= at.x.toLong() + size.width + 2 ||
                        at.y.toLong() >= other.y.toLong() + previous.height + 2 ||
                        other.y.toLong() >= at.y.toLong() + size.height + 2)
            }
        }
        return layout
    }

    @Test fun tallHeaderAndVisibleFragmentsDoNotBecomeOneLongTextureStrip() {
        val sizes = List(24) { IntSize(512, 512) } + IntSize(1080, 2135)
        val layout = verify(sizes, 1080)
        assertTrue("Use a two-dimensional allocation", layout.size.width > 1082)
        assertTrue("Avoid the old over-eight-thousand-pixel strip", layout.size.height < 8192)
        assertTrue(layout.size.width < 8192)
        val fragments = listOf(IntSize(1080, 2135)) + List(75) { IntSize(216, 180) }
        val packed = verify(fragments, 1080)
        val occupied = fragments.sumOf { (it.width.toLong() + 2) * (it.height.toLong() + 2) }
        assertTrue("A tall header must not reserve empty width across its whole height",
            packed.size.width.toLong() * packed.size.height <= occupied * 5 / 4)
    }

    @Test fun mixedRegionsRetainInputIdentityAndDeterministicGutters() {
        val sizes = listOf(IntSize(13, 7), IntSize(31, 200), IntSize(80, 16), IntSize(31, 200))
        val first = verify(sizes, 300)
        assertEquals(first, verify(sizes, 300))
        assertTrue(first.size.width >= 302)
        assertNotEquals(first.origins[1], first.origins[3])
    }

    @Test fun seededPortraitLandscapeAndThinFragmentsNeverOverlap() {
        val random = Random(387466)
        repeat(200) {
            verify(List(random.nextInt(1, 40)) { IntSize(random.nextInt(1, 1200), random.nextInt(1, 1600)) })
        }
    }

    @Test fun invalidOrUnrepresentableAllocationsFailBeforeNativeSubmission() {
        listOf(emptyList(), listOf(IntSize(0, 1)), listOf(IntSize(1, 0)),
            listOf(IntSize(Int.MAX_VALUE, Int.MAX_VALUE))).forEach { sizes ->
            assertThrows(IllegalArgumentException::class.java) { packPinchAtlas(sizes, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { packPinchAtlas(listOf(IntSize(1, 1)), -1) }
    }
}
