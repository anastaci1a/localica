package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Failure artifacts retain exact pixels and channel-code errors; they never change acceptance. */
internal fun savePlayerEqualityEvidence(label: String, before: IntArray, after: IntArray, width: Int, height: Int) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val folder = File(root, "player-equality").apply { mkdirs() }
    val name = label.replace(Regex("[^a-zA-Z0-9_.-]"), "-")
    fun write(suffix: String, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    write("reference", before); write("candidate", after)
    var count = 0
    var max = 0
    var sum = 0L
    var left = width; var top = height; var right = -1; var bottom = -1
    val samples = JSONArray()
    val diff = IntArray(before.size) { i ->
        val a = before[i]; val b = after[i]
        if (a == b) 0xFF000000.toInt() else {
            count++
            val x = i % width; val y = i / width
            left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
            var local = 0
            for (shift in listOf(0,8,16,24)) {
                val error = abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
                local = maxOf(local,error); sum += error
            }
            max = maxOf(max,local)
            if (samples.length() < 32) samples.put(JSONArray(listOf(x,y,a,b,local)))
            0xFF000000.toInt() or (minOf(255,local*16) shl 16) or 0x00000080
        }
    }
    write("diff-x16", diff)
    val metrics = JSONObject().put("label",label).put("width",width).put("height",height)
        .put("changedPixels",count).put("maxChannelCode",max).put("sumChannelCode",sum)
        .put("meanAbsoluteRGBA",sum.toDouble()/(width.toDouble()*height*4))
        .put("changedBounds",JSONArray(listOf(left,top,right,bottom))).put("samples",samples)
    File(folder,"$name.json").writeText(metrics.toString(2))
}

