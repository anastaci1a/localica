package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The artwork reveal's exact frames (#526, #588): the text and action wait through every frame before the 300 ms reveal
 * ends and are released together on the first frame at or after its end. The start frame is logged rather than pinned;
 * the same file runs unchanged against the earlier Animatable reveal as the matched arm. */
class ArtworkRevealReleaseFrameTest {
    @get:Rule val compose = createComposeRule()
    private val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }

    @Test fun textAndActionReleaseOnTheFirstFrameAtTheEndOfTheReveal() {
        val text = ArtworkTextReveal()
        var image by mutableStateOf<Bitmap?>(null)
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalicaTheme {
                ArtworkImage(image?.asImageBitmap(), loading, Modifier.size(64.dp).testTag("reveal"), RectangleShape, textReveal = text)
            }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertTrue(text.waiting) }
        compose.runOnIdle { image = red; loading = false }
        val arrived = compose.mainClock.currentTime
        val frames = mutableListOf<String>()
        var started = -1L
        var released = -1L
        var lastWaiting = -1L
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            val t = compose.mainClock.currentTime - arrived
            compose.runOnIdle {
                val alpha = text.textAlpha
                frames += "$t:${"%.4f".format(alpha)}:${if (text.waiting) "W" else "R"}"
                if (started < 0 && alpha > 0f) started = t
                if (text.waiting) lastWaiting = t else if (released < 0) released = t
                if (!text.waiting) assertEquals("Text and action are released together at $t", 1f, text.menuAlpha(1f), 0f)
            }
        }
        Log.i("ArtworkRevealFrames", "started=$started released=$released lastWaiting=$lastWaiting frames=$frames")
        val frame = 16L
        assertTrue("The reveal starts within three frames of the art: $frames", started in 1..3 * frame)
        // The first sampled frame with alpha above zero is one frame after the reveal's own start.
        val origin = started - frame
        assertTrue("The text still waits on the last frame before the reveal's end: $frames", lastWaiting < origin + 300)
        assertTrue("The text is released on the first frame at or after the reveal's end: $frames",
            released >= origin + 300 && released < origin + 300 + frame)
        assertEquals("No frame between the last wait and the release: $frames", lastWaiting + frame, released)
    }
}
