package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkWashDepthTest {
    @Test fun upperBlurResolvesContinuouslyInsteadOfJumpingAtPointTwo() {
        for (x in listOf(0f, .25f, .5f, .75f, 1f)) {
            assertEquals(1f, ArtworkWash.blurDepth(x, .2f), .0001f)
            assertEquals(ArtworkWash.blurDepth(x, .2f - .0001f), ArtworkWash.blurDepth(x, .2f + .0001f), .0001f)
            assertEquals(0f, ArtworkWash.blurDepth(x, .4f), .0001f)
            var last = ArtworkWash.blurDepth(x, .15f)
            for (step in 1..300) {
                val next = ArtworkWash.blurDepth(x, .15f + step / 1000f)
                assertTrue("No blur step at x=$x step=$step", kotlin.math.abs(next - last) < .008f)
                last = next
            }
        }
    }
}
