package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Composite screenshots include both the real native search window and its clipped home. */
class SearchHomeFieldContinuityTest {
    @get:Rule val compose=localicaComposeRule()

    @Test fun aReturningNativeFaceAndItsRestingClippedHomeKeepOneContinuousField() {
        var visible by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var sample: SearchMorphSample?=null
        compose.setContent { LocalicaTheme {
            anchor=rememberGlassAnchor()
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength=1f,shadowSpread=1f),
                LocalSearchMorphObserver provides { sample=it }) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    GlassShadowScope(Modifier.padding(top=100.dp,start=20.dp,end=20.dp).fillMaxWidth().height(56.dp)) {
                        Box(Modifier.fillMaxWidth().clipToBounds()) {
                            ListSearchPill("Search fixture",{visible=true},Modifier.fillMaxWidth().padding(vertical=4.dp),anchor)
                        }
                    }
                }
                ListSearchWindow(visible,anchor,false,{visible=false}) {
                    val ready=LocalSearchOpeningReady.current
                    val focused=LocalWindowInfo.current.isWindowFocused
                    LaunchedEffect(visible,focused) { if(visible && focused) { withFrameNanos{};ready?.invoke(true) } }
                    Box(Modifier.fillMaxWidth().height(56.dp).padding(16.dp)) { Text("Search fixture") }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val wasDrawing=androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        if(!wasDrawing)androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        val output=nativeEvidenceDirectory("search-home-field-continuity",File(instrumentation.targetContext.getExternalFilesDir(null),"search-home-field-continuity")).apply { mkdirs() }
        fun tick() { compose.mainClock.advanceTimeByFrame();compose.waitForIdle();android.os.SystemClock.sleep(8) }
        fun until(condition: ()->Boolean) {
            val start=android.os.SystemClock.uptimeMillis()
            while(!condition() && android.os.SystemClock.uptimeMillis()-start<5000)tick()
            assertTrue("The actual native source/window lifecycle reaches its bound",condition())
        }
        fun capture(name: String): ImageBitmap {
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            return bitmap.asImageBitmap()
        }
        try {
            repeat(8){tick()}
            val home=anchor.presentedBounds
            val node=requireNotNull(anchor.surfaceNode)
            val d=compose.density.density
            val probe=ShadowFrameProbe(Rect(home.right-52*d,home.bottom+12*d,home.right-36*d,home.bottom+16*d))
            val baseline=probe.sample(capture("home-rest"))
            assertTrue("The resting field extends below the old slot clip",baseline<.99f)
            fun opened()=sample?.let { it.opening && it.progress>=.999f }==true
            compose.runOnIdle{visible=true}
            until(::opened)
            assertSame("Native expansion leases the real home node",node,anchor.returningNode)
            compose.runOnIdle{visible=false}
            repeat(4){tick()}
            compose.runOnIdle{visible=true}
            until(::opened)
            assertSame("Reopen keeps the same source lifetime",node,anchor.returningNode)
            compose.runOnIdle{visible=false}
            var frames=0
            val log=StringBuilder("frame,progress,modal,sourceOwns,mean\n")
            val start=android.os.SystemClock.uptimeMillis()
            while(anchor.modalPresent && android.os.SystemClock.uptimeMillis()-start<5000) {
                tick()
                val current=sample
                if(current!=null && !current.opening && current.progress<.02f &&
                    listOf(abs(current.bounds.left-home.left),abs(current.bounds.top-home.top),abs(current.bounds.right-home.right),abs(current.bounds.bottom-home.bottom)).max()<=.5f) {
                    val mean=probe.sample(capture("settle-$frames"))
                    log.append("$frames,${current.progress},${anchor.modalPresent},${anchor.shadowPlane?.let { node.ownsWindow(it) }},$mean\n")
                    frames++
                }
            }
            assertFalse("Native window retires after source admission",anchor.modalPresent)
            repeat(3) { tick();val mean=probe.sample(capture("home-final-$it"));log.append("final$it,0,false,true,$mean\n") }
            File(output,"settle.csv").writeText(log.toString())
            assertTrue("Observe the near-rest native face as well as the source home",frames>0)
            assertSame(node,anchor.surfaceNode)
            probe.assertContinuous(.008f,"Native search field to clipped scoped home, including settle and reopen")
        } finally { if(!wasDrawing)androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false) }
    }
}
