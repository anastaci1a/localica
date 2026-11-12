package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** Inspect hardware snapshots without changing the saved native recording or its pixels. */
internal fun r1InspectionBitmap(original: Bitmap, output: File, name: String): Bitmap {
    val png = File(output, "$name.png")
    png.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    if (original.config != Bitmap.Config.HARDWARE) return original
    var inspection: Bitmap? = null
    try {
        val copy = requireNotNull(original.copy(Bitmap.Config.ARGB_8888, false))
        inspection = copy
        assertEquals("R1 inspection copy preserves color space", original.colorSpace, copy.colorSpace)
        val copyPng = File(output, "$name-inspection.png")
        copyPng.outputStream().use { assertTrue(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decodedOriginal = requireNotNull(BitmapFactory.decodeFile(png.path, options))
        val decodedCopy = requireNotNull(BitmapFactory.decodeFile(copyPng.path, options))
        var changed = 0
        try {
            assertEquals(decodedOriginal.width, decodedCopy.width)
            assertEquals(decodedOriginal.height, decodedCopy.height)
            val before = IntArray(decodedOriginal.width)
            val after = IntArray(decodedCopy.width)
            for (y in 0 until decodedOriginal.height) {
                decodedOriginal.getPixels(before, 0, before.size, 0, y, before.size, 1)
                decodedCopy.getPixels(after, 0, after.size, 0, y, after.size, 1)
                changed += before.indices.count { before[it] != after[it] }
            }
            File(output, "$name-inspection.json").writeText(JSONObject()
                .put("originalConfig", original.config.toString()).put("inspectionConfig", copy.config.toString())
                .put("colorSpace", original.colorSpace.toString()).put("width", original.width).put("height", original.height)
                .put("decodedRgbaChanged", changed).toString(2))
            assertEquals("R1 hardware inspection copy preserves every decoded RGBA pixel", 0, changed)
        } finally { decodedOriginal.recycle(); decodedCopy.recycle() }
        return copy
    } catch (failure: Throwable) { inspection?.recycle(); throw failure }
    finally { original.recycle() }
}
