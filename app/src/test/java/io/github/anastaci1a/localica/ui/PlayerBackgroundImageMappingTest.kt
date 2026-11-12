package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.playerArtworkContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import org.junit.Assert.*
import org.junit.Test

class PlayerBackgroundImageMappingTest {
    @Test fun fitRoundsDestinationBeforeCenteringInsteadOfGuessingTheCrop() {
        val source = Size(3f, 2f)
        val frame = Rect(0f, 0f, 10f, 10f)
        assertEquals(Rect(0f, 2f, 10f, 9f), playerBackgroundImageBounds(source, frame, ContentScale.Fit))
        assertEquals(Rect(-2f, 0f, 13f, 10f), playerBackgroundImageBounds(source, frame, ContentScale.Crop))
        assertEquals(Rect(2f, 0f, 9f, 10f), playerBackgroundImageBounds(Size(2f, 3f), frame, ContentScale.Fit))
    }

    @Test fun suppliedPainterPolicyOwnsBothAxesAndHalfPixelRounding() {
        val policy = object : ContentScale {
            override fun computeScaleFactor(srcSize: Size, dstSize: Size) = ScaleFactor(2.5f, 1.5f)
        }
        assertEquals(Rect(2f, 4f, 10f, 7f),
            playerBackgroundImageBounds(Size(3f, 2f), Rect(0f, 0f, 11f, 11f), policy))
    }

    @Test fun aspectRatiosInsetsAndClippingRetainTheSameImageCoordinates() {
        for (source in listOf(Size(7f, 4f), Size(4f, 7f), Size(7f, 7f))) {
            for (frame in listOf(Rect(0f, 0f, 301f, 157f), Rect(0f, 0f, 157f, 301f))) {
                for (retention in listOf(0f, .4f, 1f)) {
                    val policy = playerArtworkContentScale(32f, retention, false)
                    val image = requireNotNull(playerBackgroundImageBounds(source, frame, policy))
                    val translated = Rect(frame.left + 17f, frame.top + 29f, frame.right + 17f, frame.bottom + 29f)
                    assertEquals(Rect(image.left + 17f, image.top + 29f, image.right + 17f, image.bottom + 29f),
                        playerBackgroundImageBounds(source, translated, policy))
                    val footprint = playerBackgroundFootprints(translated, Rect(0f, 50f, 400f, 200f),
                        listOf(Rect(30f, 20f, 130f, 240f))).single()
                    assertEquals(50f, footprint.top, 0f)
                    assertTrue(footprint.bottom <= translated.bottom && footprint.bottom <= 200f)
                    // Clipping changes the sampled footprint, never the image's scale or origin.
                    assertEquals(image.width, requireNotNull(playerBackgroundImageBounds(source, translated, policy)).width, 0f)
                }
            }
        }
    }

    @Test fun invalidOrUndrawableDestinationsCannotInventPixels() {
        val frame = Rect(0f, 0f, 10f, 10f)
        for (source in listOf(Size.Zero, Size(Float.NaN, 1f), Size(1f, Float.POSITIVE_INFINITY)))
            assertNull(playerBackgroundImageBounds(source, frame, ContentScale.Fit))
        val tooSmall = object : ContentScale {
            override fun computeScaleFactor(srcSize: Size, dstSize: Size) = ScaleFactor(.01f, .01f)
        }
        assertNull(playerBackgroundImageBounds(Size(1f, 1f), frame, tooSmall))
    }
}
