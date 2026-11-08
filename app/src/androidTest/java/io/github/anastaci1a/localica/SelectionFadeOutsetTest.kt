package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SelectionFadeOutsetTest {
    @get:Rule val compose=localicaComposeRule()
    @Test fun fieldOutsetPreservesBodyMaskViewportRangeAndRowsForFitAndOverflow() {
        var expanded by mutableStateOf(false)
        var count by mutableIntStateOf(8)
        lateinit var grid: LazyGridState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent { LocalicaTheme {
            grid=rememberLazyGridState()
            scope=rememberCoroutineScope()
            val d=androidx.compose.ui.platform.LocalDensity.current
            val pad=glassShadowInset()+with(d) { 2f.toDp() }
            Box(Modifier.size(320.dp,300.dp).background(Color.Black).testTag("fade-scene")) {
                Box(Modifier.offset(40.dp,60.dp).size(240.dp,160.dp)
                    .scrollEdgeFade(grid,if(expanded)pad else 0.dp).drawWithContent {
                        // A field witness spanning both edges, beneath the very same mask as body pixels.
                        drawRect(Color.White,Offset(-20.dp.toPx(),-20.dp.toPx()),Size(size.width+40.dp.toPx(),size.height+40.dp.toPx()))
                        drawContent()
                    }) {
                    LazyVerticalGrid(GridCells.Fixed(1),Modifier.fillMaxSize(),state=grid,overscrollEffect=null) {
                        items(count) { index -> Box(Modifier.fillMaxWidth().height(50.dp).drawWithContent {
                            drawRect(if(index%2==0)Color.Red else Color.Green,Offset(100.dp.toPx(),10.dp.toPx()),Size(20.dp.toPx(),20.dp.toPx()))
                        }) }
                    }
                }
            }
        } }
        val scene=compose.onNodeWithTag("fade-scene")
        val d=compose.density.density
        val output=nativeEvidenceDirectory("selection-fade-outset",File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"selection-fade-outset")).apply { mkdirs() }
        val report=StringBuilder("count,index,offset,max,oldLayout,newLayout\n")
        fun layout()=listOf(grid.layoutInfo.viewportSize,grid.layoutInfo.viewportStartOffset,grid.layoutInfo.viewportEndOffset,
            grid.canScrollBackward,grid.canScrollForward,grid.layoutInfo.visibleItemsInfo.map { listOf(it.index,it.offset,it.size) })
        for((case,state) in listOf(Triple(2,0,0),Triple(8,0,0),Triple(8,1,12),Triple(8,7,0)).withIndex()) {
            compose.runOnIdle { count=state.first; scope.launch { grid.scrollToItem(state.second,(state.third*d).toInt()) }; expanded=false }
            compose.waitForIdle()
            val old=scene.captureNativeFrame()
            val oldLayout=layout()
            compose.runOnIdle { expanded=true }
            compose.waitForIdle()
            val next=scene.captureNativeFrame()
            val nextLayout=layout()
            assertEquals("Physical native grid viewport/range and every placed row stay unchanged",oldLayout,nextLayout)
            val a=old.toPixelMap();val b=next.toPixelMap()
            var max=0
            for(y in (60*d).toInt() until (220*d).toInt()) for(x in (40*d).toInt() until (280*d).toInt()) {
                val p=a[x,y];val q=b[x,y]
                max=maxOf(max,(maxOf(kotlin.math.abs(p.red-q.red),kotlin.math.abs(p.green-q.green),kotlin.math.abs(p.blue-q.blue))*255).toInt())
            }
            report.append("${state.first},${state.second},${state.third},$max,$oldLayout,$nextLayout\n")
            for((name,image)in listOf("old" to old,"outset" to next)) File(output,"$case-$name.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            assertEquals("The original body-domain soft fade must stay byte-identical, case=$state",0,max)
            val x=(160*d).toInt()
            if(!grid.canScrollBackward) assertTrue("Top field spill survives when that edge is at rest",b[x,(52*d).toInt()].red>.9f)
            else assertTrue("The same top soft fade also masks the field outside the body clip",b[x,(52*d).toInt()].red<1f/255)
            if(!grid.canScrollForward) assertTrue("Bottom field spill survives when that edge is at rest",b[x,(228*d).toInt()].red>.9f)
            else assertTrue("The same bottom soft fade also masks the field outside the body clip",b[x,(228*d).toInt()].red<1f/255)
        }
        File(output,"parity.csv").writeText(report.toString())
    }
}
