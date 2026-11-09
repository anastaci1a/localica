package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
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

/** Unrounded character boxes; admission tests the pixel centre and excludes protected content. */
internal data class PlayerAlbumPixels(
    val suffix: List<androidx.compose.ui.geometry.Rect>,
    val protected: List<androidx.compose.ui.geometry.Rect>
) {
    fun allows(x: Int, y: Int): Boolean {
        val centre = androidx.compose.ui.geometry.Offset(x + .5f, y + .5f)
        return suffix.any { it.contains(centre) } && protected.none { it.contains(centre) }
    }
}

/** The fixture uses only axis-aligned scale/translation. Fail closed for other transforms. */
internal fun measuredPlayerAlbumSuffix(compose: androidx.compose.ui.test.junit4.ComposeContentTestRule): PlayerAlbumPixels {
    val text = compose.onNodeWithText("Artist · Album", useUnmergedTree = true)
    val artist = text.fetchSemanticsNode()
    val rootCoordinates = compose.onNodeWithTag("frame-root").fetchSemanticsNode().layoutInfo.coordinates
    val suffix = mutableListOf<androidx.compose.ui.geometry.Rect>()
    val protected = mutableListOf<androidx.compose.ui.geometry.Rect>()
    val nodes = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
        androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
    nodes.forEach { node ->
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.runOnIdle {
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val coordinates = node.layoutInfo.coordinates
            results.forEach { layout ->
                for (i in 0 until layout.layoutInput.text.length) {
                    val box = layout.getBoundingBox(i)
                    val a = rootCoordinates.localPositionOf(coordinates, box.topLeft)
                    val b = rootCoordinates.localPositionOf(coordinates, box.bottomRight)
                    val c = rootCoordinates.localPositionOf(coordinates, box.topRight)
                    val d = rootCoordinates.localPositionOf(coordinates, box.bottomLeft)
                    check(a.y == c.y && a.x == d.x && b.y == d.y && b.x == c.x) {
                        "Album oracle requires axis-aligned character geometry"
                    }
                    val transformed = androidx.compose.ui.geometry.Rect(minOf(a.x,b.x), minOf(a.y,b.y), maxOf(a.x,b.x), maxOf(a.y,b.y))
                    if (node.id == artist.id && i >= "Artist".length) suffix += transformed
                    else protected += transformed
                }
            }
        }
    }
    // Protect non-text content adjacent to the artist as well as every other text character.
    listOf("player-artwork", "player-title", "player-transport-row").forEach { tag ->
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            compose.runOnIdle { protected += rootCoordinates.localBoundingBoxOf(node.layoutInfo.coordinates, clipBounds = false) }
        }
    }
    check(suffix.isNotEmpty()) { "Missing album character geometry" }
    return PlayerAlbumPixels(suffix.toList(), protected.toList())
}

/** Export each arm even when an earlier sample later fails comparison. */
internal fun savePlayerAlbumMask(label: String, width: Int, height: Int, mask: PlayerAlbumPixels?) {
    var regionPixels = 0
    var rawOverlap = 0
    var admittedProtected = 0
    for (y in 0 until height) for (x in 0 until width) {
        val centre = androidx.compose.ui.geometry.Offset(x + .5f, y + .5f)
        val protected = mask?.protected?.any { it.contains(centre) } == true
        val raw = mask?.suffix?.any { it.contains(centre) } == true
        if (protected && raw) rawOverlap++
        if (mask?.allows(x,y) == true) {
            regionPixels++
            if (protected) admittedProtected++
        }
    }
    val metrics = JSONObject().put("label",label).put("regionPixels",regionPixels)
        .put("rawSuffixProtectedOverlap",rawOverlap).put("admittedProtectedOverlap",admittedProtected)
        .put("suffixCharacterBoxes",JSONArray(mask?.suffix?.map { it.toString() } ?: emptyList<String>()))
        .put("protectedBoxes",JSONArray(mask?.protected?.map { it.toString() } ?: emptyList<String>()))
        .put("membership","pixel centre in suffix character box AND outside every protected box; no rounding or halo")
    writePlayerRegionEvidence(label + "-mask", metrics)
    org.junit.Assert.assertEquals("Album allowance must be disjoint from protected content: $metrics",0,admittedProtected)
}

private fun writePlayerRegionEvidence(label: String, metrics: JSONObject) {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val root=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val folder=File(root,"player-equality").apply { mkdirs() }
    File(folder,label.replace(Regex("[^a-zA-Z0-9_.-]"),"-")+"-region.json").writeText(metrics.toString(2))
}

/** Lead-approved album-only bound. Every pixel outside the measured suffix remains exact. */
internal fun assertPlayerPixelsWithAlbumBound(label: String, before: IntArray, after: IntArray,
    width: Int, height: Int, suffix: PlayerAlbumPixels?) {
    require(before.size == width * height && after.size == before.size)
    var exactChanged=0; var regionPixels=0; var regionChanged=0; var maximum=0; var rgbSum=0L
    for(i in before.indices) {
        val inSuffix=suffix?.allows(i%width,i/width)==true
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
        .put("regionPixels",regionPixels).put("regionChanged",regionChanged)
        .put("maxChannelCode",maximum).put("changedFraction",changedFraction).put("meanAbsoluteRGB",meanRGB)
    writePlayerRegionEvidence(label,metrics)
    org.junit.Assert.assertEquals("Exact pixels outside album suffix: $metrics",0,exactChanged)
    org.junit.Assert.assertTrue("Album suffix bound: $metrics",maximum<=1&&meanRGB<=.05)
}
