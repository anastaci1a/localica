package io.github.anastaci1a.localica

import android.graphics.Bitmap
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerBackdropAlphaEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun neutralPaletteAlphaAndWidthReversalsMatchFrozenGroup() = compare(false)
    @Test fun washAndGrainAlphaAndChildChangesMatchFrozenGroup() = compare(true)
    @Test fun firstReceiptRejectsDelayedPublicationAndWrongOpacity() = compare(false, true)
    private data class Frame(val width:Int,val height:Int,val argb:IntArray)
    private fun capture(label:String):Frame {
        val p=compose.onNodeWithTag("scene").captureNativeFrame().toPixelMap()
        val f=Frame(p.width,p.height,IntArray(p.width*p.height) { p[it%p.width,it/p.width].toArgb() })
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"t3-alpha-equality").apply { mkdirs() }
        Bitmap.createBitmap(f.argb,f.width,f.height,Bitmap.Config.ARGB_8888).let { bitmap ->
            try { File(dir,"$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        }
        return f
    }
    private fun same(label:String,a:Frame,b:Frame) {
        assertEquals("$label width",a.width,b.width);assertEquals("$label height",a.height,b.height)
        val changed=a.argb.indices.count { a.argb[it]!=b.argb[it] }
        assertEquals("$label exact native ARGB ($changed changed pixels)",0,changed)
    }
    private fun compare(withWash:Boolean, controls:Boolean=false) {
        val wash=Bitmap.createBitmap(ArtworkWash.WIDTH,ArtworkWash.HEIGHT,Bitmap.Config.ARGB_8888).apply {
            val pixels=IntArray(width*height) { i -> if(((i%width)/7+(i/width)/9)%2==0) 0xFFEA3567.toInt() else 0xFF198ACC.toInt() }
            setPixels(pixels,0,width,0,0,width,height)
        }
        val soft=Bitmap.createScaledBitmap(wash,32,80,true)
        val palette=PlayerPalette(Color(0xFF42637B),Color(0xFF695C79),Color(0xFF293D45),
            wash=wash.takeIf { withWash },softWash=soft.takeIf { withWash })
        var reference by mutableStateOf(true)
        var opacity by mutableFloatStateOf(.375f)
        var width by mutableFloatStateOf(201.25f)
        var phase by mutableFloatStateOf(.1f)
        var epoch by mutableIntStateOf(0)
        var publication by mutableStateOf(true)
        compose.mainClock.autoAdvance=false
        compose.setContent { LocalicaTheme {
            Box(Modifier.requiredSize(240.dp,360.dp).background(Color(0xFF8C542D)).testTag("scene")) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFF1C6AB3),radius=size.width*.7f,center=Offset(size.width*.8f,size.height*.3f)) }
                Box(Modifier.width(width.dp).height(340.dp).clip(RoundedCornerShape(19.dp))) {
                    key(reference,epoch) {
                        if(publication) {
                            val modifier=Modifier.fillMaxSize()
                            if(reference) Box(modifier.graphicsLayer { alpha=opacity }) {
                                ReferencePlayerBackdrop(palette,Modifier.fillMaxSize(),fullArtBlend=.83f,
                                    artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                                    artWidth=320.5f,softness=phase)
                            } else if(Build.VERSION.SDK_INT>=33) PlayerBackdrop(palette,modifier,fullArtBlend=.83f,
                                artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                                artWidth=320.5f,softness=phase,groupAlpha=opacity)
                            else Box(modifier.graphicsLayer { alpha=opacity }) {
                                PlayerBackdrop(palette,Modifier.fillMaxSize(),fullArtBlend=.83f,
                                    artTop=17.25f+phase*7f,artHeight=173.75f+phase*31f,artLeft=-11.5f,
                                    artWidth=320.5f,softness=phase)
                            }
                        }
                    }
                    Text("Protected foreground",Modifier.padding(12.dp),color=Color.White)
                }
            }
        } }
        fun arm(old:Boolean):List<Frame> {
            compose.runOnIdle { reference=old; epoch++; publication=true; opacity=.375f; width=201.25f; phase=.1f }
            val frames=mutableListOf<Frame>()
            fun sample(label:String) { compose.mainClock.advanceTimeByFrame(); frames+=capture("${if(old) "reference" else "candidate"}-$withWash-$label") }
            sample("first")
            for((i,a) in listOf(0f,.001f,.1f,.5f,.9f,.999f,1f,.375f).withIndex()) {
                compose.runOnIdle { opacity=a; width=if(i%2==0) 201.25f else 203.75f; phase=(i+1)/9f };sample("reverse-$i")
            }
            return frames
        }
        try {
            val a=arm(true)
            if(controls) {
                compose.runOnIdle { reference=false; epoch++; publication=false; opacity=.375f; width=201.25f; phase=.1f }
                compose.mainClock.advanceTimeByFrame();val first=capture("delayed-first")
                assertFalse("First receipt must reject delayed publication",a.first().argb.contentEquals(first.argb))
                compose.runOnIdle { publication=true; opacity=.5f };compose.mainClock.advanceTimeByFrame()
                assertFalse("Known opacity difference must be detected",a.first().argb.contentEquals(capture("wrong-opacity").argb))
            } else {
                val b=arm(false);a.zip(b).forEachIndexed { i,(x,y)->same("wash=$withWash frame=$i",x,y) }
            }
        } finally { compose.mainClock.autoAdvance=true }
    }
}
