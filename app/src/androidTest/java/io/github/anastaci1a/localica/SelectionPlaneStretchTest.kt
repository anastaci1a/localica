package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native pixels correlate a source-local path cutout with an independent in-tree content edge.
 * No transform is inferred from scrolling, bounds, or the platform's deformation. */
class SelectionPlaneStretchTest {
    @get:Rule val compose=localicaComposeRule()
    @Test fun topStretchKeepsTheFieldOnItsContentEdge()=stretch(false)
    @Test fun bottomStretchKeepsTheFieldOnItsContentEdge()=stretch(true)

    private fun stretch(bottom: Boolean) {
        val target=if(bottom)4 else 0
        lateinit var view: android.view.View
        compose.setContent { LocalicaTheme {
            view=androidx.compose.ui.platform.LocalView.current
            Box(Modifier.size(320.dp,500.dp).background(Color.Black).testTag("stretch-scene")) {
                SelectionViewport(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(400.dp)) { effect ->
                    val state=rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().testTag("stretch-list"),state=state,overscrollEffect=effect) {
                        items(5) { index ->
                            val plane=rememberShadowPlane()
                            val field=rememberNativePathField(plane,index,ShadowRole.Glow)
                            Box(Modifier.fillMaxWidth().height(200.dp).drawWithContent {
                                field.beginFrame()
                                try {
                                    if(index==target) {
                                        val r=Rect(90.dp.toPx(),50.dp.toPx(),190.dp.toPx(),130.dp.toPx())
                                        val path=Path().apply { addRect(r) }
                                        field.draw(this,index,path,Offset.Zero,1f,1f,Color.White)
                                        drawRect(Color.Cyan,Offset(30.dp.toPx(),r.top),Size(20.dp.toPx(),2.dp.toPx()))
                                    }
                                    drawContent()
                                } finally { field.endFrame() }
                            })
                        }
                    }
                }
                Box(Modifier.offset(270.dp,20.dp).size(20.dp).background(Color.Magenta))
            }
        } }
        val list=compose.onNodeWithTag("stretch-list")
        if(bottom)list.performScrollToIndex(4)
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val scene=compose.onNodeWithTag("stretch-scene")
        val d=compose.density.density
        val sceneBounds=scene.fetchSemanticsNode().boundsInRoot
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val windowOrigin=IntArray(2)
        val screenOrigin=IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(windowOrigin); view.getLocationOnScreen(screenOrigin) }
        val crop=android.graphics.Rect((sceneBounds.left+windowOrigin[0]).roundToInt(),(sceneBounds.top+windowOrigin[1]).roundToInt(),(sceneBounds.right+windowOrigin[0]).roundToInt(),(sceneBounds.bottom+windowOrigin[1]).roundToInt())
        var context=view.context
        while(context !is android.app.Activity) context=(context as android.content.ContextWrapper).baseContext
        val window=(context as android.app.Activity).window
        fun nativeFrame(): ImageBitmap {
            val drawn=java.util.concurrent.CountDownLatch(1)
            val listener=android.view.ViewTreeObserver.OnDrawListener { view.post { drawn.countDown() } }
            instrumentation.runOnMainSync { view.viewTreeObserver.addOnDrawListener(listener); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); view.invalidate() }
            try {
                assertTrue("Native stretched traversal completes",drawn.await(5,java.util.concurrent.TimeUnit.SECONDS))
                return copyNativeWindowOnce(window,android.graphics.Bitmap.createBitmap(crop.width(),crop.height(),android.graphics.Bitmap.Config.ARGB_8888),"Stretched native window copy timed out",crop) { "Stretched native window copy failed: $it" }.asImageBitmap()
            } finally { instrumentation.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(listener) } }
        }
        val directory=nativeEvidenceDirectory("selection-plane-stretch",File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"selection-plane-stretch")).apply { mkdirs() }
        val report=StringBuilder("frame,referenceTop,fieldCutoutTop,fixedTop\n")
        fun capture(frame: Int): Triple<Int,Int,Int> {
            val image=nativeFrame()
            File(directory,"${if(bottom) "bottom" else "top"}-$frame.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            val p=image.toPixelMap()
            val markerX=(40*d).roundToInt()
            val marker=(0 until p.height).filter { y -> p[markerX,y].green>.8f && p[markerX,y].blue>.8f && p[markerX,y].red<.2f }
            assertTrue("The independent content edge is visible",marker.isNotEmpty())
            val top=marker.min()
            val fieldX=(140*d).roundToInt()
            val cutout=(top-5..top+5).firstOrNull { y -> p[fieldX,y].red<1f/255f && p[fieldX,y-2].red>2f/255f }
            assertNotNull("The path cutout edge must remain beside the content edge at frame=$frame top=$top",cutout)
            val fixedX=(280*d).roundToInt()
            val fixed=(0 until p.height).first { y -> p[fixedX,y].red>.8f && p[fixedX,y].blue>.8f && p[fixedX,y].green<.2f }
            return Triple(top,requireNotNull(cutout),fixed).also { report.append("$frame,${it.first},${it.second},${it.third}\n") }
        }
        val wasDrawing=androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        if(!wasDrawing) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        val rest=capture(0)
        val edgeDelta=rest.second-rest.first
        var moved=false
        val downTime=android.os.SystemClock.uptimeMillis()
        val x=sceneBounds.left+screenOrigin[0]+280*d
        val startY=sceneBounds.top+screenOrigin[1]+300*d
        var y=startY
        fun input(action: Int) {
            val event=android.view.MotionEvent.obtain(downTime,android.os.SystemClock.uptimeMillis(),action,x,y,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,false)) } finally { event.recycle() }
        }
        input(android.view.MotionEvent.ACTION_DOWN)
        try {
            repeat(5) { i ->
                android.os.SystemClock.sleep(32)
                y+=(if(bottom)-24f else 24f)*d
                input(android.view.MotionEvent.ACTION_MOVE)
                val frame=capture(i+1)
                moved=moved || abs(frame.first-rest.first)>1
                assertTrue("Field and content share the native deformation, frame=$i delta=${frame.second-frame.first} rest=$edgeDelta",abs((frame.second-frame.first)-edgeDelta)<=1)
                assertEquals("Fixed chrome stays outside the stretch",rest.third,frame.third)
            }
        } finally {
            input(android.view.MotionEvent.ACTION_UP)
            if(!wasDrawing) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
        File(directory,"${if(bottom) "bottom" else "top"}.csv").writeText(report.toString())
        if(Build.VERSION.SDK_INT>=31)assertTrue("The witness must contain actual platform stretch",moved)
    }
}
