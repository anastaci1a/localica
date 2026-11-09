package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.RetainedGlassShadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Diagnostic only: force the two existing renderer branches with identical geometry and inputs.
 * Reports their actual pixel difference; it grants no production equality allowance. */
class ShadowRasterIsolationTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun liveAndPreparedShadowBranchesAreIndependentlyCaptured() {
        val cancelled = SupervisorJob().apply { cancel() }
        val liveScope = CoroutineScope(cancelled + Dispatchers.Main.immediate)
        lateinit var live: RetainedGlassShadow
        lateinit var prepared: RetainedGlassShadow
        var redraw by mutableIntStateOf(0)
        compose.setContent {
            val scope=rememberCoroutineScope()
            live=remember { RetainedGlassShadow(liveScope) }
            prepared=remember(scope) { RetainedGlassShadow(scope) }
            Column {
                listOf("live" to live,"prepared" to prepared).forEach { (tag,cache) ->
                    Canvas(Modifier.size(340.dp,220.dp).background(Color(0xFFE1E2E3)).testTag(tag)) {
                        redraw
                        val inset=24.dp.toPx()
                        val body=RoundRect(inset,inset,size.width-inset,size.height-inset,CornerRadius(31.37.dp.toPx()))
                        with(cache) { draw(listOf(body to 1f),strength=.23f,spread=.42f) }
                    }
                }
            }
        }
        try {
            compose.waitUntil(10000) { prepared.rasterizations>0 }
            compose.runOnIdle { redraw++ }
            compose.waitForIdle()
            fun capture(tag:String):Triple<IntArray,Int,Int> {
                val image=compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                return Triple(IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() },image.width,image.height)
            }
            val (a,width,height)=capture("live")
            val (b,otherWidth,otherHeight)=capture("prepared")
            assertEquals(width,otherWidth);assertEquals(height,otherHeight)
            assertEquals("Cancelled preparation guarantees the historical live branch",0,live.rasterizations)
            assertTrue("Other arm must actually use a prepared raster",prepared.rasterizations>0)
            savePlayerEqualityEvidence("shadow-live-versus-prepared",a,b,width,height)
            android.util.Log.i("PERF2_SHADOW","sameInputs=true liveRasterizations=${live.rasterizations} preparedRasterizations=${prepared.rasterizations} changed=${a.indices.count { a[it]!=b[it] }}")
        } finally { liveScope.cancel() }
    }
}
