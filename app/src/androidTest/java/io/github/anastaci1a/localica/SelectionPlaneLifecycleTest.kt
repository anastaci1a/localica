package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionPlaneLifecycleTest {
    @get:Rule val compose=localicaComposeRule()

    private fun kernels(field: NativePathField): Map<*,*> = field.javaClass.getDeclaredField("kernels").apply { isAccessible=true }.get(field) as Map<*,*>
    private fun jobs(field: NativePathField)=kernels(field).values.map { kernel ->
        requireNotNull(kernel).javaClass.getDeclaredField("lifetime").apply { isAccessible=true }.get(kernel) as Job
    }

    @Test fun emptyUnmountRecreateAndInkChangesRetireTheActualSource() {
        var mounted by mutableStateOf(true)
        var empty by mutableStateOf(false)
        var glow by mutableStateOf(true)
        lateinit var plane: ShadowPlane
        lateinit var field: NativePathField
        val key=Any()
        val contourKey=Any()
        compose.setContent { LocalicaTheme {
            plane=rememberShadowPlane()
            Box(Modifier.size(240.dp).background(Color.Gray).testTag("lifetime-scene")) {
                if(mounted) {
                    val current=rememberNativePathField(plane,key,if(glow) ShadowRole.Glow else ShadowRole.Lift)
                    field=current
                    Box(Modifier.fillMaxSize().drawWithContent {
                        current.beginFrame()
                        try {
                            if(!empty) {
                                val path=Path().apply { addRoundRect(RoundRect(Rect(50.dp.toPx(),50.dp.toPx(),170.dp.toPx(),140.dp.toPx()),CornerRadius(16.dp.toPx()))) }
                                current.draw(this,contourKey,path,Offset.Zero,1f,1f,if(glow) Color.White else Color.Black)
                            }
                        } finally { current.endFrame() }
                    })
                }
            }
        } }
        val scene=compose.onNodeWithTag("lifetime-scene")
        scene.captureNativeFrame()
        val originalField=field
        val originalNode=plane.casters.single()
        val originalFrame=field.outline()
        val originalJobs=jobs(field)
        assertFalse(originalFrame.path.isEmpty)
        assertTrue(originalJobs.isNotEmpty())
        compose.runOnIdle { empty=true }
        scene.captureNativeFrame()
        assertTrue(field.outline().path.isEmpty)
        assertFalse("A published contour cannot mutate when its source clears",originalFrame.path.isEmpty)
        assertTrue(kernels(field).isEmpty())
        assertTrue(originalJobs.all { it.isCancelled })
        compose.runOnIdle { empty=false; glow=false }
        scene.captureNativeFrame()
        assertSame(originalField,field)
        assertSame(originalNode,plane.casters.single())
        assertEquals(ShadowRole.Lift,plane.casters.single().role)
        assertEquals(1f,plane.casters.single().opacity.value(),0f)
        val nextJobs=jobs(field)
        assertTrue(nextJobs.isNotEmpty())
        compose.runOnIdle { mounted=false }
        scene.captureNativeFrame()
        assertTrue(plane.casters.isEmpty())
        assertTrue(kernels(originalField).isEmpty())
        assertTrue(nextJobs.all { it.isCancelled })
        compose.runOnIdle { mounted=true; glow=true }
        scene.captureNativeFrame()
        assertNotSame(originalField,field)
        assertEquals(1,plane.casters.size)
        assertEquals(ShadowRole.Glow,plane.casters.single().role)
    }

    @Test fun aRetainedChildCanRedrawWithoutLosingOrDuplicatingTheSourceField() {
        var childRevision by mutableIntStateOf(0)
        var sourceDraws=0
        var childDraws=0
        lateinit var plane: ShadowPlane
        lateinit var field: NativePathField
        val key=Any()
        compose.setContent { LocalicaTheme {
            plane=rememberShadowPlane()
            field=rememberNativePathField(plane,key,ShadowRole.Glow)
            Box(Modifier.size(240.dp).background(Color.Black).testTag("retained-scene").graphicsLayer()
                .drawWithContent {
                    sourceDraws++
                    field.beginFrame()
                    try {
                        val path=Path().apply { addRoundRect(RoundRect(Rect(50.dp.toPx(),50.dp.toPx(),170.dp.toPx(),140.dp.toPx()),CornerRadius(16.dp.toPx()))) }
                        field.draw(this,key,path,Offset.Zero,1f,1f,Color.White)
                        drawContent()
                    } finally { field.endFrame() }
                }) {
                Box(Modifier.fillMaxSize().graphicsLayer().drawWithContent {
                    childDraws++
                    drawRect(if(childRevision==0) Color.Red else Color.Green,
                        Offset(80.dp.toPx(),80.dp.toPx()),Size(20.dp.toPx(),20.dp.toPx()))
                })
            }
        } }
        val scene=compose.onNodeWithTag("retained-scene")
        val before=scene.captureNativeFrame().toPixelMap()
        val oldSource=sourceDraws
        val oldChild=childDraws
        compose.runOnIdle { childRevision++ }
        val after=scene.captureNativeFrame().toPixelMap()
        val d=compose.density.density
        val x=(90*d).toInt(); val y=(90*d).toInt()
        assertTrue(before[x,y].red>.9f && after[x,y].green>.9f)
        assertTrue(childDraws>oldChild)
        assertEquals("Only the retained child needs to record again",oldSource,sourceDraws)
        val shadowX=(100*d).toInt();val shadowY=(150*d).toInt()
        assertEquals(before[shadowX,shadowY],after[shadowX,shadowY])
        assertEquals(1,plane.casters.size)
        assertFalse(field.outline().path.isEmpty)
    }
}
