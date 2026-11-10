package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/** M6 v1. The independent exact oracle remains in PlayerBackdropAlphaEqualityTest. */
class PlayerBackdropAlphaApproximationTest {
    @get:Rule val compose = localicaComposeRule()
    private data class Frame(val width: Int, val height: Int, val pixels: IntArray)
    private val output by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "t3-alpha-m6").apply { mkdirs() } }
    @Test fun sharedCoversWithFrozenProtectedMask() {
        val input = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "t3-covers")
        val manifest = JSONObject(File(input, "manifest.json").readText())
        assertEquals(Build.VERSION.SDK_INT, manifest.getInt("api"))
        val covers = manifest.getJSONArray("covers")
        assertEquals(18, covers.length())
        val palettes = (0 until covers.length()).map { i ->
            val row = covers.getJSONObject(i); val file = File(input, row.getString("file"))
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(row.getString("sha256"), sha)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
            try { runBlocking { playerPalette(bitmap) } } finally { bitmap.recycle() }
        }
        File(output, "manifest.json").writeText(manifest.toString(2))
        var old by mutableStateOf(true)
        var selected by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        var opacity by mutableFloatStateOf(.375f)
        var width by mutableFloatStateOf(201.25f)
        var phase by mutableFloatStateOf(.1f)
        var ready by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var publication by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme { GlassPanelTheme(dark) {
            Box(Modifier.requiredSize(240.dp,360.dp).background(if(dark) Color(0xFF15181C) else Color(0xFFEAE4DB)).testTag("scene")) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFF1C6AB3), radius=size.width*.7f, center=Offset(size.width*.8f,size.height*.3f)) }
                Box(Modifier.width(width.dp).height(340.dp).clip(RoundedCornerShape(19.dp))) {
                    key(old,epoch) { if(publication) {
                        val palette=palettes[selected]
                        val modifier=Modifier.fillMaxSize()
                        if(old) Box(modifier.graphicsLayer { alpha=opacity }) {
                            ReferencePlayerBackdrop(palette,Modifier.fillMaxSize(),fullArtBlend=.83f,
                                artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                                artWidth=320.5f,softness=phase,ready=ready)
                        } else if(Build.VERSION.SDK_INT>=33) PlayerBackdrop(palette,modifier,fullArtBlend=.83f,
                            artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                            artWidth=320.5f,softness=phase,ready=ready,groupAlpha=opacity)
                        else Box(modifier.graphicsLayer { alpha=opacity }) {
                            PlayerBackdrop(palette,Modifier.fillMaxSize(),fullArtBlend=.83f,
                                artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                                artWidth=320.5f,softness=phase,ready=ready)
                        }
                    } }
                    Text("Protected foreground",Modifier.padding(12.dp),color=if(dark) Color.White else Color.Black)
                }
            }
        } } }
        val rows=JSONArray(); val failures=mutableListOf<String>()
        fun capture(name:String):Frame {
            compose.mainClock.advanceTimeByFrame()
            val pixels=compose.onNodeWithTag("scene").captureNativeFrame().toPixelMap()
            val frame=Frame(pixels.width,pixels.height,IntArray(pixels.width*pixels.height) { pixels[it%pixels.width,it/pixels.width].toArgb() })
            Bitmap.createBitmap(frame.pixels,frame.width,frame.height,Bitmap.Config.ARGB_8888).let { bitmap ->
                try { File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            }
            return frame
        }
        // Frozen mask v1: complete rounded backdrop minus 2px rim and full conservative text rectangle.
        fun affected(x:Int,y:Int,w:Int,frame:Frame):Boolean {
            val scale=frame.width/240.0; val xx=x+.5; val yy=y+.5; val height=340*scale; val radius=19*scale
            if(xx<2 || xx>w-2 || yy<2 || yy>height-2) return false
            if(xx>=12*scale && xx<=190*scale && yy>=12*scale && yy<=48*scale) return false
            val cx=xx.coerceIn(radius,w-radius); val cy=yy.coerceIn(radius,height-radius)
            return hypot(xx-cx,yy-cy)<=radius-2
        }
        fun compare(label:String,a:Frame,b:Frame,dp:Float) {
            assertEquals(a.width,b.width);assertEquals(a.height,b.height)
            val max=Array(2) { IntArray(4) }; val sum=Array(2) { LongArray(4) }; val count=IntArray(2);val changed=IntArray(2)
            val mask=IntArray(a.pixels.size)
            for(i in a.pixels.indices) {
                val region=if(affected(i%a.width,i/a.width,(dp*a.width/240).roundToInt(),a)) 0 else 1
                mask[i]=if(region==0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(); count[region]++
                var differs=false
                for(c in 0..3) { val shift=(3-c)*8; val delta=abs((a.pixels[i] ushr shift and 255)-(b.pixels[i] ushr shift and 255))
                    max[region][c]=max(max[region][c],delta);sum[region][c]+=delta; differs=differs || delta!=0 }
                if(differs) changed[region]++
            }
            Bitmap.createBitmap(mask,a.width,a.height,Bitmap.Config.ARGB_8888).let { bitmap ->
                try { File(output,"mask-$dp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            }
            val row=JSONObject().put("label",label).put("width",a.width).put("height",a.height).put("maskVersion",1)
            for(region in 0..1) { row.put(if(region==0) "affected" else "protected",JSONObject().put("pixels",count[region]).put("changed",changed[region])
                .put("maxARGB",JSONArray(max[region].toList())).put("meanARGB",JSONArray(sum[region].map { it.toDouble()/count[region] }))) }
            val pass=max[0][0]==0 && max[0].drop(1).all { it<=2 } && sum[0].drop(1).all { it.toDouble()/count[0]<=1 } && changed[1]==0
            row.put("pass",pass);rows.put(row);if(!pass) failures+=label
            File(output,"results.json").writeText(JSONObject().put("api",Build.VERSION.SDK_INT).put("rows",rows).put("failures",JSONArray(failures)).toString(2))
        }
        val alphas=listOf(.375f,0f,.001f,.0011f,.1f,.5f,.9f,.9989f,.999f,1f,.375f,.375f,.375f)
        try {
            for(index in palettes.indices) for(theme in listOf(false,true)) {
                compose.runOnIdle { selected=index;dark=theme }
                val name=covers.getJSONObject(index).getString("name")+"-"+theme
                fun arm(reference:Boolean):List<Frame> {
                    compose.runOnIdle { old=reference;epoch++;opacity=.375f;width=201.25f;phase=.1f;ready=true;publication=true }
                    return alphas.mapIndexed { i,alpha ->
                        if(i>0) compose.runOnIdle { opacity=alpha;width=if(i%2==0) 201.25f else 203.75f;phase=(i+1)/14f;ready=i!=11 }
                        capture("${if(reference) "reference" else "candidate"}-$name-$i")
                    }
                }
                val a=arm(true);val b=arm(false)
                a.indices.forEach { i -> compare("$name-$i",a[i],b[i],if(i==0 || i%2==0) 201.25f else 203.75f) }
            }
            // Use the first fixture after the sweep for independent oracle controls.
            compose.runOnIdle { selected=0;dark=false;old=true;epoch++;opacity=.375f;width=201.25f;phase=.1f;ready=true }
            val reference=capture("control-reference")
            compose.runOnIdle { old=false;epoch++;publication=false }
            assertFalse("Delayed first publication must be detected",reference.pixels.contentEquals(capture("control-delayed").pixels))
            compose.runOnIdle { publication=true;opacity=.5f }
            assertFalse("Changed alpha must be detected",reference.pixels.contentEquals(capture("control-wrong-alpha").pixels))
            File(output,"controls.json").writeText("{\"delayedPublicationDetected\":true,\"wrongAlphaDetected\":true}")
            assertTrue("M6 failed ${failures.size}/${rows.length()} frames: ${failures.take(12)}",failures.isEmpty())
        } finally { compose.mainClock.autoAdvance=true }
    }
}
