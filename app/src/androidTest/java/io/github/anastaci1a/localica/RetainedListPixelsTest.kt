package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class RetainedListPixelsTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun rowsAndFadeRetainExactPixelsAcrossContentAndGeometryChanges() {
        var title by mutableStateOf("Short title")
        var width by mutableStateOf(180.dp)
        var paused by mutableStateOf(true)
        var strength by mutableFloatStateOf(1f)
        var color by mutableStateOf(Color.Red)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color(0xFF204060)).testTag("pixels")) {
                MarqueeTitle(title, false, Modifier.width(width),
                    TextStyle(fontSize = 19.sp, fontFamily = FontFamily.Monospace, color = Color.White), paused)
                Canvas(Modifier.size(width, 140.dp).listTopFade { strength }) {
                    drawRect(Color.White)
                    repeat(18) { i -> drawCircle(color.copy(alpha = .6f), 23.3f,
                        Offset((i * 19.7f) % size.width, i * 17.3f)) }
                }
            }
        } }
        val receipts = mutableListOf<String>()
        fun capture(label: String, advance: Long = 64): String {
            compose.mainClock.advanceTimeBy(advance)
            val node = compose.onNodeWithTag("pixels")
            val bitmap = node.captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            receipts += "$label ${bitmap.width} ${bitmap.height} ${node.fetchSemanticsNode().boundsInRoot} $hash"
            return hash
        }
        val first = capture("first")
        compose.runOnIdle { color = Color.Green }
        assertNotEquals("The pixel oracle must detect a child-only update", first, capture("child-update"))
        for (value in listOf(.5f, 0f, 1f)) {
            compose.runOnIdle { strength = value }
            capture("fade-$value")
        }
        compose.runOnIdle { title = "Overflowing title with repeating ink"; width = 110.dp; paused = false }
        capture("overflow-rest")
        capture("moving", 2500)
        compose.runOnIdle { paused = true; width = 400.dp }
        capture("reversal", 80)
        capture("returned", 700)
        compose.runOnIdle { title = "Fits" }
        capture("fitting-again")
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        directory.mkdirs()
        File(directory, "retained-list-pixels.txt").writeText(receipts.joinToString("\n", postfix = "\n"))
        compose.mainClock.autoAdvance = true
    }
}
