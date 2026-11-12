package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File

/** Exact retiring-trigger field signature on the unchanged left-tab assertion region. */
internal fun verifyR1ReferenceClass(preOpen: Bitmap, reference: Bitmap, tabs: Rect,
    hostAt: IntArray, homeOrigin: Offset, densityDpi: Int, output: File) {
    val histogram = sortedMapOf<Int, Int>()
    var selected = 0
    var otherChanged = 0
    for (y in tabs.top.toInt() until tabs.bottom.toInt())
        for (x in tabs.left.toInt() until tabs.center.x.toInt()) {
            val sx = x + hostAt[0] - homeOrigin.x.toInt()
            val sy = y + hostAt[1] - homeOrigin.y.toInt()
            val before = preOpen.getPixel(sx, sy)
            val after = reference.getPixel(sx, sy)
            selected++
            if (before == after) continue
            val delta = Color.red(after) - Color.red(before)
            if (Color.alpha(before) != Color.alpha(after) ||
                Color.green(after) - Color.green(before) != delta ||
                Color.blue(after) - Color.blue(before) != delta) otherChanged++
            else histogram[delta] = histogram.getOrDefault(delta, 0) + 1
        }
    val api = Build.VERSION.SDK_INT
    val expected = when {
        api == 26 && densityDpi == 420 && reference.width == 1080 && reference.height == 1731 -> mapOf(1 to 557)
        api == 36 && densityDpi == 280 && reference.width == 720 && reference.height == 1280 -> mapOf(-1 to 294)
        else -> error("R1 reference class must be pinned for API $api, density $densityDpi, ${reference.width}x${reference.height}")
    }
    File(output, "reference-class.json").writeText(JSONObject().put("api", api).put("densityDpi", densityDpi)
        .put("width", reference.width).put("height", reference.height).put("selected", selected)
        .put("tabBounds", tabs.toString()).put("hostScreenX", hostAt[0]).put("hostScreenY", hostAt[1])
        .put("homeOriginX", homeOrigin.x).put("homeOriginY", homeOrigin.y)
        .put("changed", histogram.values.sum() + otherChanged).put("otherChanged", otherChanged)
        .put("grayscaleHistogram", JSONObject(histogram.mapKeys { it.key.toString() }))
        .put("expectedHistogram", JSONObject(expected.mapKeys { it.key.toString() })).toString(2))
    assertEquals("R1 reference correction has no other changed pixel", 0, otherChanged)
    assertEquals("R1 reference correction matches the exact published trigger-shadow class", expected, histogram)
}
