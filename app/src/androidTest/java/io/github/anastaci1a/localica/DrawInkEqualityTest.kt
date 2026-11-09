package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exact native paint equality; provider changes must not execute candidate content. */
class DrawInkEqualityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun liveWholeTextAndAlbumSpanMatchMaterialWithoutComposition() = compare(false, true)
    @Test fun liveWholeTextWithoutSpanMatchesMaterialWithoutComposition() = compare(false, false)
    @Test fun liveIconTintAndAlphaMatchMaterialWithoutComposition() = compare(true)

    private fun compare(icon: Boolean, suffixEnabled: Boolean = true) {
        var phase by mutableFloatStateOf(0f)
        var dark by mutableStateOf(false)
        val counters = InkCounters()
        val errors = mutableListOf<String>()
        fun ink(): Color = lerp(Color(0xFF426B97), Color(0xFFE7A65C), phase).copy(alpha = .72f)
        fun suffix(): Color = ink().let { it.copy(alpha = it.alpha * phase) }
        val producer = ColorProducer { ink() }
        val span = DrawInkSpanBrush(ColorProducer { suffix() })
        val prefix = "Artist العربية אבג e\u0301"
        val album = " · Album ffi Ω"
        val candidateText = buildAnnotatedString {
            append(prefix)
            if (suffixEnabled) withStyle(SpanStyle(brush = span, alpha = 1f)) { append(album) }
        }
        compose.setContent { LocalicaTheme {
            Column {
                Box(Modifier.requiredSize(340.dp, 64.dp).background(if (dark) Color.Black else Color.White).testTag("reference")) {
                    if (icon) Icon(appIconPainter(AppIcon.ActionRepeat), null, Modifier.size(37.dp).graphicsLayer { alpha = .43f }, tint = ink())
                    else Text(buildAnnotatedString {
                        append(prefix)
                        if (suffixEnabled) withStyle(SpanStyle(color = suffix())) { append(album) }
                    }, modifier = Modifier.graphicsLayer { alpha = .43f }, style = MaterialTheme.typography.bodyLarge.copy(color = ink()), maxLines = 1, softWrap = false)
                }
                Box(Modifier.requiredSize(340.dp, 64.dp).background(if (dark) Color.Black else Color.White).testTag("candidate")) {
                    MeasuredInkCandidate(icon, candidateText, producer, counters)
                }
            }
        } }
        fun pixels(tag: String): IntArray {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return IntArray(image.width * image.height) { i -> image[i % image.width, i / image.width].toArgb() }
        }
        compose.waitForIdle()
        for (background in listOf(false, true)) {
            compose.runOnIdle { dark = background }
            compose.waitForIdle()
            val admitted = counters.compositions
            val measured = counters.measures
            for (next in listOf(0f, .01f, .17f, .499f, .72f, .99f, 1f, .37f, .66f, 0f)) {
                compose.runOnIdle { phase = next }
                compose.waitForIdle()
                val old = pixels("reference")
                val current = pixels("candidate")
                var maximum = 0
                var sum = 0L
                var changed = 0
                old.indices.forEach { i ->
                    if (old[i] != current[i]) changed++
                    for (shift in listOf(0, 8, 16, 24)) {
                        val d = kotlin.math.abs((old[i] ushr shift and 255) - (current[i] ushr shift and 255))
                        maximum = maxOf(maximum, d); sum += d
                    }
                }
                val label = "phase=$next dark=$background icon=$icon suffix=$suffixEnabled"
                android.util.Log.i("PERF2_INK", "$label pixels=${old.size} changed=$changed maxCode=$maximum sumCode=$sum meanCode=${sum.toDouble() / (old.size * 4)} compositions=${counters.compositions - admitted} measures=${counters.measures - measured}")
                if (changed != 0) errors += "$label changed=$changed maxCode=$maximum"
                if (counters.compositions != admitted) errors += "$label composed ${counters.compositions - admitted}"
                if (counters.measures != measured) errors += "$label measured ${counters.measures - measured}"
            }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun snapshotOnlySpanMutationInvalidatesDrawAndMissingDependencyControlStaysFrozen() {
        val ink = DrawInk(Color(0x00426B97))
        var compositions = 0
        var measures = 0
        val positive = DrawInkSpanBrush(ColorProducer { ink.color })
        val negative = DrawInkSpanBrush(ColorProducer {
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { ink.color }
        })
        fun label(brush: DrawInkSpanBrush) = buildAnnotatedString {
            append("Artist")
            withStyle(SpanStyle(brush = brush, alpha = 1f)) { append(" · Album العربية ffi") }
        }
        val text = label(positive)
        val brokenText = label(negative)
        compose.setContent { LocalicaTheme {
            Column {
                listOf("live" to text, "missing-dependency" to brokenText).forEach { (tag, value) ->
                    SideEffect { compositions++ }
                    Box(Modifier.requiredSize(340.dp, 64.dp).background(Color(0xFFE1D7CA)).testTag(tag)) {
                        DrawInkText(value, ColorProducer { Color(0xFF426B97) },
                            Modifier.graphicsLayer { alpha = .43f }.layout { child, limits ->
                                measures++
                                val placeable = child.measure(limits)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false)
                    }
                }
            }
        } }
        fun pixels(tag: String): IntArray {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return IntArray(image.width * image.height) { i -> image[i % image.width, i / image.width].toArgb() }
        }
        compose.waitForIdle()
        val before = pixels("live")
        val frozen = pixels("missing-dependency")
        val initialCompositions = compositions
        val initialMeasures = measures
        for (color in listOf(Color(0x7F9B426D), Color(0xFF3A8E67), Color(0x003A8E67))) {
            // This is the only mutation. No text, annotation, parent or layout state changes.
            compose.runOnUiThread { ink.color = color }
            compose.waitForIdle()
            val current = pixels("live")
            assertEquals(initialCompositions, compositions)
            assertEquals(initialMeasures, measures)
            assertArrayEquals("Missing provider dependency must keep its recorded pixels", frozen, pixels("missing-dependency"))
            if (color.alpha > 0f) assertFalse("The observed shader must redraw on the next frame", before.contentEquals(current))
            else assertArrayEquals("Zero-alpha return must remove the album again", before, current)
        }
    }

}

private class InkCounters { var compositions = 0; var measures = 0 }

/** A distinct restart scope measures the actual consumer, separate from reference ink reads. */
@Composable private fun MeasuredInkCandidate(icon: Boolean, text: AnnotatedString,
    producer: ColorProducer, counters: InkCounters) {
    SideEffect { counters.compositions++ }
    val measured = Modifier.graphicsLayer { alpha = .43f }.layout { child, limits ->
        counters.measures++
        val p = child.measure(limits)
        layout(p.width, p.height) { p.place(0, 0) }
    }
    if (icon) DrawInkIcon(appIconPainter(AppIcon.ActionRepeat), null, producer, measured.size(37.dp))
    else DrawInkText(text, producer, modifier = measured, style = MaterialTheme.typography.bodyLarge,
        maxLines = 1, softWrap = false)
}
