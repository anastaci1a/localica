package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Separate the static color, live color, span and scale paths at the failing artist fraction. */
class PlayerArtistRasterEqualityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun albumMaskRejectsProtectedBoundaryAndFractionalRoundingMutations() {
        val width = 128
        val height = 64
        val before = IntArray(width * height) { 0xFF404040.toInt() }
        val suffix = androidx.compose.ui.geometry.Rect(50.75f, 20.25f, 110.25f, 40.75f)
        // Deliberate overlap also tests subtraction independently of the suffix boundary.
        val prefix = androidx.compose.ui.geometry.Rect(0f, 20.25f, 51.75f, 40.75f)
        val mask = PlayerAlbumPixels(listOf(suffix), listOf(prefix))
        savePlayerAlbumMask("boundary-mutation-reference", width, height, mask)
        savePlayerAlbumMask("boundary-mutation-candidate", width, height, mask)
        for (x in listOf(49, 50, 51, 110)) {
            val mutated = before.copyOf().apply { this[25 * width + x] += 0x00010000 }
            val failure = try {
                assertPlayerPixelsWithAlbumBound("reject-boundary-x$x", before, mutated, width, height, mask)
                null
            } catch (error: AssertionError) { error }
            assertNotNull("Protected/fractional boundary mutation x$x must fail", failure)
            assertTrue("Must reject as an exact-region failure", failure!!.message!!.startsWith("Exact pixels outside album suffix"))
        }
        val allowed = before.copyOf().apply { this[25 * width + 52] += 0x00010000 }
        assertPlayerPixelsWithAlbumBound("admit-interior-one-code", before, allowed, width, height, mask)
    }

    @Test fun scaledArtistRasterPathsAreIndependentlyComparedToTheOriginal() {
        var variant by mutableStateOf("reference")
        var phase by mutableFloatStateOf(.08f)
        var epoch by mutableIntStateOf(0)
        val ink = Color.Black.copy(alpha=.72f)
        val producer = ColorProducer { ink }
        val span = DrawInkSpanBrush(ColorProducer { Color.Black.copy(alpha=0f) })
        compose.mainClock.autoAdvance=false
        compose.setContent { LocalicaTheme {
            key(variant,epoch) {
                val scale=11f/MaterialTheme.typography.bodyLarge.fontSize.value
                val value=scale+(1f-scale)*phase
                val legacy=buildAnnotatedString {
                    append("Artist")
                    withStyle(SpanStyle(color=ink.copy(alpha=0f))) { append(" · Album") }
                }
                val shader=remember(span) { buildAnnotatedString {
                    withStyle(SpanStyle(color=ink)) { append("Artist") }
                    withStyle(SpanStyle(brush=span,alpha=1f)) { append(" · Album") }
                } }
                val unprotected=remember(span) { buildAnnotatedString {
                    append("Artist")
                    withStyle(SpanStyle(brush=span,alpha=1f)) { append(" · Album") }
                } }
                Box(Modifier.size(340.dp,90.dp).background(Color(0xFFE1E2E3)).testTag("artist-raster")) {
                    val modifier=if(variant=="reference") Modifier.fillMaxWidth().scalePlayerText(value)
                        else Modifier.fillMaxWidth().scalePlayerText { value }
                    MarqueeTitle(if(variant=="unprotected-shader") unprotected else if(variant=="producer-and-shader"||variant=="shader-only") shader else legacy,
                        false,modifier,style=MaterialTheme.typography.bodyLarge.copy(
                            color=if(variant=="producer-and-shader"||variant=="producer-only") Color.Unspecified else ink),
                        visiblePrefixLength=6,suffixVisibility=0f,
                        ink=producer.takeIf { variant=="producer-and-shader"||variant=="producer-only" })
                }
            }
        } }
        fun capture(name: String,p: Float): Pair<IntArray,Pair<Int,Int>> {
            compose.runOnIdle { variant=name;phase=p;epoch++ }
            repeat(8) { compose.mainClock.advanceTimeByFrame();compose.waitForIdle() }
            val image=compose.onNodeWithTag("artist-raster").captureToImage().toPixelMap()
            return IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() } to (image.width to image.height)
        }
        val failures=mutableListOf<String>()
        try {
            for(p in listOf(0f,.08f,.38f,1f)) {
                val (old,size)=capture("reference",p)
                if (p == .08f) {
                    val (control,_) = capture("unprotected-shader",p)
                    val changed = old.indices.count { old[it]!=control[it] }
                    if (changed>0) savePlayerEqualityEvidence("artist-negative-control-p$p",old,control,size.first,size.second)
                    if (android.os.Build.VERSION.SDK_INT == 26) assertTrue("API 26 witness must detect the original shader perturbation",changed>0)
                }
                for(name in listOf("reader-only","producer-only","shader-only","producer-and-shader")) {
                    val (current,currentSize)=capture(name,p)
                    assertEquals(size,currentSize)
                    val changed=old.indices.count { old[it]!=current[it] }
                    android.util.Log.i("PERF2_ARTIST","phase=$p variant=$name changed=$changed")
                    if(changed>0) {
                        savePlayerEqualityEvidence("artist-$name-p$p",old,current,size.first,size.second)
                        failures+="phase=$p variant=$name changed=$changed"
                    }
                }
            }
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        } finally { compose.mainClock.autoAdvance=true }
    }
}
