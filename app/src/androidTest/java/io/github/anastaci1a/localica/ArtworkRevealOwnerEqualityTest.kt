package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkRevealOwnerEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun allRevealFramesAndGatesMatchFrozenOwner() {
        fun image(color: Color) = ImageBitmap(48, 48).also {
            Canvas(it).drawRect(0f, 0f, 48f, 48f, Paint().apply { this.color = color })
        }
        val a = image(Color(0xFF3479A6))
        val b = image(Color(0xFFE7A659))
        var artwork by mutableStateOf<ImageBitmap?>(a)
        var loading by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        var epoch by mutableIntStateOf(0)
        var mounted by mutableStateOf(true)
        var reference by mutableStateOf(true)
        var run by mutableIntStateOf(0)
        var currentOwner: ArtworkTextReveal? = null
        val shimmer = mutableFloatStateOf(.25f)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced, LocalLoadingShimmer provides shimmer) {
                // Both arms occupy exactly the same screen coordinates, including rounded edges.
                Box(Modifier.requiredSize(80.dp).background(Color(0xFFCFD4D9)).testTag("frame")) {
                    key(run, reference) {
                        if (mounted) {
                            val owner = remember(epoch) { ArtworkTextReveal() }
                            SideEffect { currentOwner = owner }
                            if (reference) ReferenceArtworkImage(artwork, loading, Modifier.fillMaxSize(), textReveal = owner)
                            else ArtworkImage(artwork, loading, Modifier.fillMaxSize(), textReveal = owner)
                        }
                    }
                }
            }
        } }
        data class Frame(val label: String, val pixels: IntArray, val waiting: Boolean?, val text: Float?, val menu: Float?)
        fun captureArm(old: Boolean): List<Frame> {
            compose.runOnIdle {
                artwork = a; loading = false; reduced = false; epoch = 0; mounted = true
                currentOwner = null; reference = old; run++
            }
            val frames = mutableListOf<Frame>()
            fun sample(label: String, advance: Long = 16) {
                compose.mainClock.advanceTimeBy(advance)
                compose.waitForIdle()
                val bitmap = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
                val pixels = IntArray(bitmap.width * bitmap.height) { i -> bitmap[i % bitmap.width, i / bitmap.width].toArgb() }
                var frame: Frame? = null
                compose.runOnIdle {
                    val owner = currentOwner.takeIf { mounted }
                    frame = Frame(label, pixels, owner?.waiting, owner?.textAlpha, owner?.menuAlpha(.63f))
                }
                frames += requireNotNull(frame)
            }
            fun change(label: String, body: () -> Unit) { compose.runOnIdle(body); sample(label) }
            sample("cached initial")
            change("missing first frame") { artwork = null; loading = false; epoch++ }
            sample("missing", 400)
            change("loading admission") { loading = true; epoch++ }
            change("art arrival") { artwork = a; loading = false }
            sample("arriving early", 32)
            change("replace text owner") { epoch++ }
            sample("replaced text/menu owner", 64)
            change("absent reversal") { artwork = null; loading = true }
            sample("absent reversal moving", 32)
            change("reverse present") { artwork = b; loading = false }
            sample("reverse present moving", 64)
            sample("placeholder removing frame", 256)
            sample("placeholder removed", 16)
            change("resolution replacement") { artwork = a }
            sample("resolution replacement moving", 64)
            change("interrupted resolution") { artwork = b }
            sample("interrupted resolution settled", 256)
            change("reduced loading") { reduced = true; artwork = null; loading = true; epoch++ }
            change("reduced arrival") { artwork = a; loading = false }
            sample("reduced settled", 16)
            change("disposed") { mounted = false }
            change("reuse loading") { mounted = true; reduced = false; artwork = null; loading = true }
            change("reuse arrival") { artwork = b; loading = false }
            sample("reused owner settled", 400)
            return frames
        }
        try {
            val original = captureArm(true)
            val candidate = captureArm(false)
            original.zip(candidate).forEach { (a, b) ->
                assertEquals(a.label, b.label)
                assertArrayEquals(a.label, a.pixels, b.pixels)
                assertEquals("${a.label} text gate", a.waiting, b.waiting)
                assertEquals("${a.label} text opacity", a.text, b.text)
                assertEquals("${a.label} menu opacity", a.menu, b.menu)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
