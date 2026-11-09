package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** #711: frame detection that lets an artwork's border lead the wash beyond it; borderless covers find no frame. */
class ArtworkWashEdgeBandTest {
    private val size = ArtworkWash.WIDTH

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    private fun cover(seed: Long, pixel: (Random, Int, Int) -> Int): IntArray {
        val random = Random(seed)
        return IntArray(size * size) { pixel(random, it % size, it / size) }
    }
    private fun framed(frame: Int, thickness: Int, interior: (Random) -> Int) = cover(1) { r, x, y ->
        if (x < thickness || y < thickness || x >= size - thickness || y >= size - thickness) frame else interior(r)
    }
    private fun bands(pixels: IntArray) = ArtworkWash.edgeBand(pixels, size, bottom = false) to ArtworkWash.edgeBand(pixels, size, bottom = true)

    @Test fun solidFramesAreFoundOnBothEdgesWithTheirColourAndThickness() {
        for ((frame, thickness, interior) in listOf(
            Triple(rgb(0, 0, 0), 10) { r: Random -> rgb(236 + r.nextInt(20), 236 + r.nextInt(20), 236 + r.nextInt(20)) },
            Triple(rgb(255, 255, 255), 12) { r: Random -> rgb(18 + r.nextInt(20), 26 + r.nextInt(20), 70 + r.nextInt(30)) },
            Triple(rgb(110, 20, 32), 4) { r: Random -> rgb(246 + r.nextInt(10), 236 + r.nextInt(12), 196 + r.nextInt(16)) })) {
            val (top, bottom) = bands(framed(frame, thickness, interior))
            for (band in listOf(top, bottom)) {
                assertNotNull("A $thickness px frame is a border", band)
                assertEquals(frame, band!!.color)
                assertEquals(thickness, band.thickness)
                // Full contrast; frames of 4% or more lead fully, thinner ones in proportion (never below 0.2).
                assertEquals((thickness / (size * .04f)).coerceIn(.2f, 1f), band.strength, 1e-6f)
            }
        }
    }

    @Test fun strengthGrowsWithThicknessUpToFourPercent() {
        fun strength(thickness: Int) = ArtworkWash.edgeBand(framed(rgb(0, 0, 0), thickness) { rgb(250, 250, 250) }, size, bottom = false)!!.strength
        assertEquals(.2f, strength(2), 1e-6f)                  // 0.8% hairline: floor
        assertEquals(4f / (size * .04f), strength(4), 1e-6f)   // 1.6%: about 0.39
        assertEquals(5f / (size * .04f), strength(5), 1e-6f)   // 2%: about 0.49
        assertEquals(1f, strength(11), 1e-6f)                  // 4.3%: full
        assertEquals(1f, strength(26), 1e-6f)                  // 10%: full
    }

    @Test fun borderlessCoversFindNoFrame() {
        val photo = cover(11) { r, x, y -> rgb((40 + x * 180 / size + r.nextInt(16)).coerceAtMost(255),
            (60 + y * 150 / size + r.nextInt(16)).coerceAtMost(255), (200 - (x + y) * 140 / (2 * size) + r.nextInt(16)).coerceIn(0, 255)) }
        val sky = cover(14) { r, _, y -> if (y < size * 2 / 5) rgb(96, 150, 220) else rgb(30 + r.nextInt(60), 90 + r.nextInt(80), 30 + r.nextInt(40)) }
        val uniform = IntArray(size * size) { rgb(40, 120, 210) }
        val stripes = cover(13) { _, x, _ -> if (x / 8 % 2 == 0) rgb(240, 240, 240) else rgb(10, 10, 10) }
        // A frame too close to the art's own tone needs no lead: there is no seam to remove.
        val subtle = framed(rgb(200, 200, 200), 10) { rgb(214, 214, 214) }
        for ((name, pixels) in listOf("photo" to photo, "sky" to sky, "uniform" to uniform, "stripes" to stripes, "subtle" to subtle)) {
            val (top, bottom) = bands(pixels)
            assertNull("$name top", top)
            assertNull("$name bottom", bottom)
        }
        // The sky is only a uniform region at the top: the bottom edge is dense ground, so neither side is a frame.
    }

    @Test fun deepUniformRegionsAndHairlinesAreNotFrames() {
        val deep = framed(rgb(0, 0, 0), (size * .2f).toInt()) { rgb(250, 250, 250) }
        val hairline = framed(rgb(0, 0, 0), 1) { rgb(250, 250, 250) }
        assertNull(ArtworkWash.edgeBand(deep, size, bottom = false))
        assertNull(ArtworkWash.edgeBand(hairline, size, bottom = true))
    }

    @Test fun aFrameOnOneEdgeOnlyLeadsThatEdge() {
        val letterbox = cover(5) { r, _, y -> if (y < 16) rgb(0, 0, 0) else rgb(200 + r.nextInt(50), 120 + r.nextInt(80), r.nextInt(255)) }
        assertNotNull(ArtworkWash.edgeBand(letterbox, size, bottom = false))
        assertNull(ArtworkWash.edgeBand(letterbox, size, bottom = true))
    }

    @Test fun transparencyUsesTheExistingNeutralBase() {
        assertEquals(rgb(36, 43, 52), ArtworkWash.opaque(0))
        assertEquals(rgb(10, 20, 30), ArtworkWash.opaque(rgb(10, 20, 30)))
        val clear = IntArray(size * size)
        assertNull("A uniform transparent cover is not a frame", ArtworkWash.edgeBand(clear, size, bottom = false))
    }

    @Test fun theFrameLeadsAtTheEdgeAndEasesTowardTheInteriorFurtherOut() {
        assertEquals(1f, ArtworkWash.edgeWeight(0f), 1e-6f)
        var last = 1f
        for (step in 1..125) {
            val weight = ArtworkWash.edgeWeight(step / 100f)
            assertTrue("monotonic at $step", weight <= last + 1e-6f)
            assertTrue("no step at $step", last - weight < .01f)
            last = weight
        }
        assertTrue("Above the cover (at most 0.25 heights) the frame still leads", ArtworkWash.edgeWeight(.25f) > .9f)
        assertEquals(.45f, ArtworkWash.edgeWeight(1.25f), 1e-6f)
        assertEquals(.45f, ArtworkWash.edgeWeight(4f), 1e-6f)
    }
}
