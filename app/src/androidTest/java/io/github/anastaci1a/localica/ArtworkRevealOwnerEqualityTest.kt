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
        var original: ArtworkTextReveal? = null
        var candidate: ArtworkTextReveal? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                Column {
                    listOf(true, false).forEach { reference ->
                        Box(Modifier.requiredSize(80.dp).background(Color(0xFFCFD4D9))
                            .testTag(if (reference) "original" else "candidate")) {
                            if (mounted) {
                                val owner = remember(epoch) { ArtworkTextReveal() }
                                SideEffect { if (reference) original = owner else candidate = owner }
                                if (reference) ReferenceArtworkImage(artwork, loading, Modifier.fillMaxSize(), textReveal = owner)
                                else ArtworkImage(artwork, loading, Modifier.fillMaxSize(), textReveal = owner)
                            }
                        }
                    }
                }
            }
        } }
        fun pixels(tag: String): IntArray {
            val bitmap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return IntArray(bitmap.width * bitmap.height) { i -> bitmap[i % bitmap.width, i / bitmap.width].toArgb() }
        }
        fun sample(label: String, advance: Long = 16) {
            compose.mainClock.advanceTimeBy(advance)
            compose.waitForIdle()
            assertArrayEquals(label, pixels("original"), pixels("candidate"))
            if (mounted) {
                val old = requireNotNull(original)
                val current = requireNotNull(candidate)
                compose.runOnIdle {
                    assertEquals("$label text gate", old.waiting, current.waiting)
                    assertEquals("$label text opacity", old.textAlpha, current.textAlpha, 0f)
                    assertEquals("$label menu opacity", old.menuAlpha(.63f), current.menuAlpha(.63f), 0f)
                }
            }
        }
        fun change(block: () -> Unit) { compose.runOnIdle(block); sample("first next frame") }
        try {
            sample("cached initial")
            change { artwork = null; loading = false; epoch++ }
            sample("missing", 400)
            change { loading = true; epoch++ }
            change { artwork = a; loading = false }
            sample("arriving early", 32)
            change { epoch++ }
            sample("replaced text/menu owner", 64)
            change { artwork = null; loading = true }
            sample("absent reversal", 32)
            change { artwork = b; loading = false }
            sample("reverse present", 64)
            sample("placeholder removing frame", 256)
            sample("placeholder removed", 16)
            change { artwork = a }
            sample("resolution replacement", 64)
            change { artwork = b }
            sample("interrupted resolution", 256)
            change { reduced = true; artwork = null; loading = true; epoch++ }
            change { artwork = a; loading = false }
            sample("reduced settled", 16)
            change { mounted = false }
            change { mounted = true; reduced = false; artwork = null; loading = true }
            change { artwork = b; loading = false }
            sample("reused owner settled", 400)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
