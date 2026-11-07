package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackTransportTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() { IconPacks.reset(context) }
    private fun install() {
        val source = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("pack.json", """{"name":"Transport vector fixture","version":"1"}""")
            for (id in listOf("transport.play", "transport.previous", "transport.next"))
                entry("icons/$id.svg", """<svg viewBox="0 0 24 24"><path d="M4 4H20V20H4Z"/></svg>""")
            entry("icons/transport.pause.svg", """<svg viewBox="0 0 24 24"><path d="M10 3H14V21H10Z"/></svg>""")
        } }.toByteArray()
        IconPacks.import(context, source.inputStream())
    }
    private fun pixels(tag: String): List<Color> {
        val p = compose.onNodeWithTag(tag).captureNativeFrame().toPixelMap()
        return (0 until p.height).flatMap { y -> (0 until p.width).map { x -> p[x, y] } }
    }
    private fun same(expected: List<Color>, actual: List<Color>, message: String) {
        assertEquals(expected.size, actual.size)
        val error = expected.indices.sumOf { i ->
            kotlin.math.abs(expected[i].red - actual[i].red).toDouble() +
                kotlin.math.abs(expected[i].green - actual[i].green) + kotlin.math.abs(expected[i].blue - actual[i].blue)
        } / (expected.size * 3)
        assertTrue("$message; mean channel error=$error", error < .005)
    }
    @Test fun vectorTransportUsesSharedTintSizeAvailabilityAndContinuousStateTiming() {
        install()
        var playing by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            Column {
                for ((index, glyph) in TransportGlyph.entries.withIndex()) {
                    val icon = when (glyph) {
                        TransportGlyph.Previous -> AppIcon.TransportPrevious
                        TransportGlyph.Next -> AppIcon.TransportNext
                        TransportGlyph.PlayPause -> if (playing) AppIcon.TransportPause else AppIcon.TransportPlay
                    }
                    Row {
                        TransportButton(glyph, playing, "Transport $index", { playing = !playing },
                            Modifier.size(48.dp).background(Color.White).testTag("transport-$index"),
                            24.dp, Color.Red, true, false)
                        Box(Modifier.size(48.dp).background(Color.White).testTag("reference-$index"), contentAlignment = Alignment.Center) {
                            Icon(appIconPainter(icon), null, Modifier.size(24.dp), tint = Color.Red)
                        }
                    }
                }
                TransportButton(TransportGlyph.Next, false, "Disabled transport", {},
                    Modifier.size(48.dp).background(Color.White).testTag("disabled"), 24.dp, Color.Red, false, false)
                Box(Modifier.size(48.dp).background(Color.White).testTag("disabled-reference"), contentAlignment = Alignment.Center) {
                    Icon(appIconPainter(AppIcon.TransportNext), null, Modifier.size(24.dp), tint = Color.Red.copy(alpha = .38f))
                }
            }
        } }
        compose.waitForIdle()
        for (index in 0..2) same(pixels("reference-$index"), pixels("transport-$index"), "Transport $index tint and size")
        same(pixels("disabled-reference"), pixels("disabled"), "Disabled transport tint")
        val start = pixels("transport-1")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Transport 1").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(96)
        val middle = pixels("transport-1")
        assertNotEquals(start, middle)
        compose.mainClock.advanceTimeBy(600)
        same(pixels("reference-1"), pixels("transport-1"), "Pause endpoint")
        assertNotEquals(middle, pixels("transport-1"))
        compose.mainClock.autoAdvance = true
    }
}
