package io.github.anastaci1a.localica

import android.os.Build
import android.util.Log
import io.github.anastaci1a.localica.BackdropEdgeCovers.luminance
import io.github.anastaci1a.localica.BackdropEdgeCovers.washRow
import io.github.anastaci1a.localica.ui.ArtworkWash
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

/** #711: an artwork's border sets the tone of the backdrop around it; borderless covers keep today's wash. */
class ArtworkWashEdgeWeightingTest {
    private val below = (0..9).map { 1.04f + it * .04f }        // 1.04 .. 1.40 cover heights
    private val above = (0..5).map { -.24f + it * .04f }        // -0.24 .. -0.04

    /** 0 = the border's tone, 1 = the interior's tone. */
    private fun position(cover: BackdropEdgeCovers.Cover, tone: Float): Float {
        val frame = luminance(checkNotNull(cover.frame))
        return (tone - frame) / (cover.interiorLuminance - frame)
    }

    /** The frame's share of the lead: full from 4% of the cover, proportional below (floor 0.2). */
    private fun share(cover: BackdropEdgeCovers.Cover) = (cover.thickness / (BackdropEdgeCovers.SIZE * .04f)).coerceIn(.2f, 1f)

    @Test fun theBorderSetsTheToneJustOutsideTheArtwork() {
        val failures = mutableListOf<String>()
        for (cover in BackdropEdgeCovers.bordered()) {
            val wash = ArtworkWash.create(cover.bitmap)
            // A full frame brings the tone within NEAR_LIMIT of the border; a thin border moves it in proportion
            // to its share, without flooding the field with the border colour.
            val limit = 1f - (1f - NEAR_LIMIT) * share(cover)
            val floor = 1f - share(cover) - .1f
            try {
                var sum = 0f
                for (y in above + below) {
                    val p = position(cover, washRow(wash, y))
                    sum += p
                    Log.i(TAG, "tone ${cover.name} y=${"%.2f".format(y)} p=${"%.3f".format(p)}")
                    if (p > limit) failures += "${cover.name} y=${"%.2f".format(y)} p=${"%.2f".format(p)} > ${"%.2f".format(limit)}"
                }
                val mean = sum / (above.size + below.size)
                Log.i(TAG, "mean ${cover.name} p=${"%.3f".format(mean)} share=${"%.3f".format(share(cover))}")
                if (mean < floor) failures += "${cover.name} mean p=${"%.2f".format(mean)} floods below ${"%.2f".format(floor)}"
            } finally { wash.recycle() }
        }
        assertTrue("Backdrop beside the artwork must follow the border tone in proportion to its share " +
            "(p <= 1 - ${1f - NEAR_LIMIT} x share, share = clamp(thickness / 4%, 0.2, 1); 0 = border, 1 = interior): $failures",
            failures.isEmpty())
    }

    @Test fun theFieldBelowHasNoSeamAndOnlyThenBlendsTowardTheInterior() {
        val failures = mutableListOf<String>()
        for (cover in BackdropEdgeCovers.bordered()) {
            val wash = ArtworkWash.create(cover.bitmap)
            try {
                // A seam is a band of border tone that rebounds steeply to the interior tone right after it:
                // the steepest rise toward the interior over any 0.1 cover height in the first 0.6 below the cover.
                var rebound = 0f
                for (step in 0..25) {
                    val start = 1f + step * .02f
                    rebound = maxOf(rebound, position(cover, washRow(wash, start + .1f)) - position(cover, washRow(wash, start)))
                }
                Log.i(TAG, "seam ${cover.name} rebound=${"%.3f".format(rebound)}")
                // A thin border keeps part of its old seam by design: the allowance grows as its share shrinks.
                val reboundLimit = REBOUND_LIMIT + .55f * (1f - share(cover))
                if (rebound > reboundLimit) failures += "${cover.name} rebound ${"%.2f".format(rebound)} > ${"%.2f".format(reboundLimit)}"
                val far = position(cover, washRow(wash, 2.2f))
                Log.i(TAG, "far ${cover.name} p=${"%.3f".format(far)}")
                if (far < .15f) failures += "${cover.name} far field never reaches the interior colours: ${"%.2f".format(far)}"
            } finally { wash.recycle() }
        }
        assertTrue("No border seam below the artwork (rise toward the interior per 0.1 cover height <= $REBOUND_LIMIT + 0.55 x (1 - share)): $failures", failures.isEmpty())
    }

    @Test fun borderlessCoversKeepTheirExistingWash() {
        for (cover in BackdropEdgeCovers.borderless()) {
            val wash = ArtworkWash.create(cover.bitmap)
            val shade = ArtworkWash.collectionShade(wash)
            try {
                val crc = CRC32()
                for (bitmap in listOf(wash, shade)) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4)
                    bytes.asIntBuffer().put(pixels)
                    crc.update(bytes.array())
                }
                Log.i(TAG, "crc api=${Build.VERSION.SDK_INT} ${cover.name}=${java.lang.Long.toHexString(crc.value)}")
                assertEquals("${cover.name} wash and collection shade must stay byte-identical to the pre-#711 generator",
                    BORDERLESS_CRC.getValue(cover.name), java.lang.Long.toHexString(crc.value))
            } finally { shade.recycle(); wash.recycle() }
        }
    }

    private companion object {
        const val TAG = "Wash711"
        const val NEAR_LIMIT = .35f
        const val REBOUND_LIMIT = .15f
        // Recorded from the unchanged generator (de999216 + tests, 4b66336c) on API 26 and API 36: identical on both.
        val BORDERLESS_CRC = mapOf("borderless-photo" to "ff676ea2", "borderless-stripes" to "a5c2102c",
            "borderless-sky" to "3a62009e", "borderless-vignette" to "1507a5ac")
    }
}
