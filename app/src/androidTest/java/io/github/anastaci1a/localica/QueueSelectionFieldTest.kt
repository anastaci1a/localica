package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class QueueSelectionFieldTest {
    @get:Rule val compose=localicaComposeRule()

    @Test fun selectedQueueSpillsAtRestAndKeepsItsFieldUntilTheRowLeaves() {
        val queues=(0..9).map { ListeningQueue(name="Queue $it") }
        lateinit var source: QueueSelectionDrawSource
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent { LocalicaTheme {
            scope=rememberCoroutineScope()
            CompositionLocalProvider(LocalContentColor provides Color.White,
                LocalGlassAppearance provides GlassAppearance(shadowStrength=1f,shadowSpread=1f),
                LocalPopupScrollEdges provides true,
                LocalQueueSelectionDrawObserver provides { source=it }) {
                Box(Modifier.size(320.dp,300.dp).background(Color.Black).testTag("queue-field-scene")) {
                    QueueSelectorList(queues,queues[0].id,queues[0].id,false,true,true,
                        Modifier.padding(top=50.dp).height(180.dp),{},{},{},{ _,_-> },{ _,_,done->done(false) })
                }
            }
        } }
        compose.waitForIdle()
        val scene=compose.onNodeWithTag("queue-field-scene")
        val d=compose.density.density
        val output=nativeEvidenceDirectory("queue-selection-field",File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"queue-selection-field")).apply { mkdirs() }
        val report=StringBuilder("offset,rowBottom,field\n")
        fun capture(offset: Int): Pair<Float,Float> {
            val image=scene.captureNativeFrame()
            File(output,"offset-$offset.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            val origin=scene.fetchSemanticsNode().boundsInRoot.topLeft
            val viewport=source.viewport.localToRoot(androidx.compose.ui.geometry.Offset.Zero)-origin
            val selected=source.scene.lastDrawnRegions.single().bounds
            val pixels=image.toPixelMap()
            val x=(viewport.x+selected.center.x).roundToInt()
            val y=(viewport.y+selected.bottom+8*d).roundToInt()
            val value=(x-6..x+6).map { pixels[it,y].red }.average().toFloat()
            report.append("$offset,${selected.bottom},$value\n")
            return selected.bottom to value
        }
        val rest=scene.captureNativeFrame().toPixelMap()
        val origin=scene.fetchSemanticsNode().boundsInRoot.topLeft
        val viewport=source.viewport.localToRoot(androidx.compose.ui.geometry.Offset.Zero)-origin
        val selected=source.scene.lastDrawnRegions.single().bounds
        val x=(viewport.x+selected.center.x).roundToInt()
        val y=(viewport.y-8*d).roundToInt()
        val spill=(x-6..x+6).map { rest[it,y].red }.average()
        File(output,"rest-spill.txt").writeText("viewport=$viewport selected=$selected spill=$spill\n")
        val missing=mutableListOf<String>()
        for(offset in listOf(0,12,24,36,44,48)) {
            compose.runOnIdle { scope.launch { source.state.scrollToItem(0,(offset*d).roundToInt()) } }
            compose.waitForIdle()
            val (bottom,field)=capture(offset)
            if(bottom>0f && field<=1f/255f) missing+="offset=$offset bottom=$bottom field=$field"
        }
        File(output,"scroll.csv").writeText(report.toString())
        assertTrue("The selected Queue field must extend above the hard scroll-area clip at rest: $spill",spill>1.0/255)
        assertTrue("A still-visible selected row keeps a visible, soft-masked trailing field: $missing",missing.isEmpty())
    }
}
