package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** #654 diagnostic only (never merged): why the API 36 rest frame differs from the static icon. */
class SkipRestFrameDiagnosticTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private val glyph: DrawScope.() -> Unit = {
        drawTransportGlyph(TransportGlyph.Next, 0f, 0f, Color.Black, TransportShapes())
    }
    private val static: DrawScope.() -> Unit = {
        scale(1f, 1f) {
            for (x in listOf(.08f, .48f)) translate(left = x * size.width) {
                TransportShapes().skipTriangle(this, Color.Black)
            }
        }
    }
    // The static icon without the identity scale, to rule out the transform sequence.
    private val bare: DrawScope.() -> Unit = {
        for (x in listOf(.08f, .48f)) translate(left = x * size.width) {
            TransportShapes().skipTriangle(this, Color.Black)
        }
    }

    private fun software(w: Int, h: Int, content: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            content()
        }
        return bitmap
    }

    private fun diff(name: String, a: ImageBitmap, b: ImageBitmap): String {
        val x = a.toPixelMap()
        val y = b.toPixelMap()
        if (a.width != b.width || a.height != b.height) return "$name: size ${a.width}x${a.height} vs ${b.width}x${b.height}"
        var count = 0
        var max = 0f
        val first = mutableListOf<String>()
        for (j in 0 until a.height) for (i in 0 until a.width) {
            val d = abs(x[i, j].red - y[i, j].red)
            if (d > 0f) {
                count++
                if (d > max) max = d
                if (first.size < 8) first += "($i,$j ${(x[i, j].red * 255).toInt()}/${(y[i, j].red * 255).toInt()})"
            }
        }
        return "$name: $count px differ, max ${(max * 255).toInt()}/255 ${first.joinToString(" ")}"
    }

    @Test fun dumpAndDiffRestFrames() {
        val tags = listOf("glyph", "static", "static2", "glyph2", "bare")
        val bodies = listOf(glyph, static, static, glyph, bare)
        compose.setContent {
            Row {
                tags.forEachIndexed { i, tag ->
                    Canvas(Modifier.size(100.dp).background(Color.White).testTag(tag), onDraw = bodies[i])
                }
            }
        }
        compose.waitForIdle()
        val frames = tags.associateWith { compose.onNodeWithTag(it).captureNativeFrame() }
        val w = frames.getValue("glyph").width
        val h = frames.getValue("glyph").height
        val sw = mapOf("glyph" to software(w, h, glyph), "static" to software(w, h, static), "bare" to software(w, h, bare))
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)))
            .let { File(it, "skip-rest-654") }.apply { mkdirs() }
        val api = android.os.Build.VERSION.SDK_INT
        fun save(name: String, image: ImageBitmap) = File(root, "$name-api$api.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        frames.forEach { (k, v) -> save("hw-$k", v) }
        sw.forEach { (k, v) -> save("sw-$k", v) }
        val report = listOf(
            "API $api ${w}x$h density ${compose.density.density}",
            diff("hw glyph vs static (the failing check)", frames.getValue("glyph"), frames.getValue("static")),
            diff("hw static vs static2 (same code, other position)", frames.getValue("static"), frames.getValue("static2")),
            diff("hw glyph vs glyph2 (same code, other position)", frames.getValue("glyph"), frames.getValue("glyph2")),
            diff("hw glyph vs static2", frames.getValue("glyph"), frames.getValue("static2")),
            diff("hw static vs bare (identity scale)", frames.getValue("static"), frames.getValue("bare")),
            diff("sw glyph vs static (draw commands)", sw.getValue("glyph"), sw.getValue("static")),
            diff("sw static vs bare", sw.getValue("static"), sw.getValue("bare")),
            diff("hw glyph vs sw glyph", frames.getValue("glyph"), sw.getValue("glyph")),
        ).joinToString("\n")
        File(root, "report-api$api.txt").writeText(report)
        // Always fail so the report reaches the run summary on both APIs.
        fail("DIAGNOSTIC\n$report")
    }
}
