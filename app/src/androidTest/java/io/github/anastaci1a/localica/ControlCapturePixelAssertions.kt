package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.io.File

/** The original full-pixel oracle, with failure-only export of its existing inputs. */
internal fun assertControlCapturePixels(actual: ImageBitmap, expected: ImageBitmap, label: String, variant: String) {
    try {
        assertEquals(label, expected.width, actual.width)
        assertEquals(label, expected.height, actual.height)
        val a = actual.toPixelMap(); val b = expected.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            // At most one 8-bit channel quantization step; no geometric or perceptual tolerance.
            val ac = a[x, y]; val bc = b[x, y]
            // Compare ARGB8 units directly: adjacent normalized Float channels can
            // subtract to slightly more than 1f / 255f (for example, 16 and 15).
            val argb = ac.toArgb(); val expectedArgb = bc.toArgb()
            if (!(kotlin.math.abs((argb ushr 24) - (expectedArgb ushr 24)) <= 1 &&
                kotlin.math.abs(((argb ushr 16) and 255) - ((expectedArgb ushr 16) and 255)) <= 1 &&
                kotlin.math.abs(((argb ushr 8) and 255) - ((expectedArgb ushr 8) and 255)) <= 1 &&
                kotlin.math.abs((argb and 255) - (expectedArgb and 255)) <= 1))
                fail("$label at $x,$y: expected $bc, actual $ac")
        }
    } catch (failure: AssertionError) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = nativeEvidenceDirectory("control-capture-failures", context.cacheDir)
            val prefix = (variant + "-" + label).replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val images = listOf("actual" to actual, "reference" to expected)
            images.forEach { (name, image) ->
                File(directory, "${prefix}-${name}.png").outputStream().use {
                    check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            File(directory, "${prefix}.txt").writeText(buildString {
                appendLine("variant=$variant label=$label api=${Build.VERSION.SDK_INT}")
                appendLine("Original already-captured inputs; no additional capture, wait or retry; not an atomic presentation-state observation.")
                images.forEach { (name, image) ->
                    val bitmap = image.asAndroidBitmap()
                    appendLine("$name width=${image.width} height=${image.height} config=${bitmap.config} colorSpace=${bitmap.colorSpace} mutable=${bitmap.isMutable} recycled=${bitmap.isRecycled}")
                }
                appendLine("originalFailure=${failure.message}")
            })
        } catch (diagnosticFailure: Throwable) {
            if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
        }
        throw failure
    }
}
