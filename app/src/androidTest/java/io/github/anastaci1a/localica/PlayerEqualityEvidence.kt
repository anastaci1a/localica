package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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

/** Character geometry defines the only allowed region; pixel differences never choose its bounds. */
internal fun measuredPlayerAlbumSuffix(compose: androidx.compose.ui.test.junit4.ComposeContentTestRule): androidx.compose.ui.geometry.Rect {
    val text = compose.onNodeWithText("Artist · Album", useUnmergedTree = true)
    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
    text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
    val layout = results.single()
    val textCoordinates = text.fetchSemanticsNode().layoutInfo.coordinates
    val rootCoordinates = compose.onNodeWithTag("frame-root").fetchSemanticsNode().layoutInfo.coordinates
    return compose.runOnIdle {
        val boxes = ("Artist".length until layout.layoutInput.text.length).map { i ->
            val box = layout.getBoundingBox(i)
            val a = rootCoordinates.localPositionOf(textCoordinates, box.topLeft)
            val b = rootCoordinates.localPositionOf(textCoordinates, box.bottomRight)
            androidx.compose.ui.geometry.Rect(minOf(a.x,b.x), minOf(a.y,b.y), maxOf(a.x,b.x), maxOf(a.y,b.y))
        }
        androidx.compose.ui.geometry.Rect(kotlin.math.floor(boxes.minOf { it.left })-1f,
            kotlin.math.floor(boxes.minOf { it.top })-1f, kotlin.math.ceil(boxes.maxOf { it.right })+1f,
            kotlin.math.ceil(boxes.maxOf { it.bottom })+1f)
    }
}

/** Lead-approved album-only bound. Every pixel outside the measured suffix remains exact. */
internal fun assertPlayerPixelsWithAlbumBound(label: String, before: IntArray, after: IntArray,
    width: Int, height: Int, suffix: androidx.compose.ui.geometry.Rect?) {
    var exactChanged=0; var regionPixels=0; var regionChanged=0; var maximum=0; var rgbSum=0L
    for(i in before.indices) {
        val inSuffix=suffix?.contains(androidx.compose.ui.geometry.Offset(i%width+.5f,i/width+.5f))==true
        if(!inSuffix) { if(before[i]!=after[i]) exactChanged++; continue }
        regionPixels++
        if(before[i]!=after[i]) regionChanged++
        for(shift in listOf(0,8,16,24)) {
            val difference=abs(((before[i] ushr shift) and 255)-((after[i] ushr shift) and 255))
            maximum=maxOf(maximum,difference)
            if(shift<24) rgbSum+=difference
        }
    }
    val changedFraction=if(regionPixels==0) 0.0 else regionChanged.toDouble()/regionPixels
    val meanRGB=if(regionPixels==0) 0.0 else rgbSum.toDouble()/(regionPixels*3)
    val metrics=JSONObject().put("label",label).put("exactOutsideChanged",exactChanged)
        .put("suffixBounds",suffix?.toString()).put("regionPixels",regionPixels).put("regionChanged",regionChanged)
        .put("maxChannelCode",maximum).put("changedFraction",changedFraction).put("meanAbsoluteRGB",meanRGB)
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val root=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val folder=File(root,"player-equality").apply { mkdirs() }
    File(folder,label.replace(Regex("[^a-zA-Z0-9_.-]"),"-")+"-region.json").writeText(metrics.toString(2))
    org.junit.Assert.assertEquals("Exact pixels outside album suffix: $metrics",0,exactChanged)
    org.junit.Assert.assertTrue("Album suffix bound: $metrics",maximum<=1&&changedFraction<=.015&&meanRGB<=.01)
}
