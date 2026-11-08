package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
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

/** The retained legacy renderer is the optical reference. The rejected post-body field is a sensitivity control. */
class SelectionPlaneOpticsTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun glowRetainsWeightedSubsetAndCrossGroupCompositing() = compare(true)
    @Test fun liftRetainsWeightedSubsetAndCrossGroupCompositing() = compare(false)

    private fun compare(glow: Boolean) {
        var alpha by mutableFloatStateOf(0f)
        var mode by mutableIntStateOf(0)
        var ready: ()->Boolean = { false }
        fun prepared(shadow: RetainedGlassShadow): Boolean {
            val job=shadow.javaClass.getDeclaredField("preparation").apply { isAccessible=true }.get(shadow) as? kotlinx.coroutines.Job
            return job?.isCompleted==true
        }
        fun groupReady(group: HoverGroupRenderer): Boolean {
            val surfaces=group.javaClass.getDeclaredField("surfaces").apply { isAccessible=true }.get(group) as Map<*,*>
            return surfaces.isNotEmpty() && surfaces.values.all { prepared((it as HoverSurfaceRenderer).shadow) }
        }
        fun fieldReady(field: NativePathField): Boolean {
            val kernels=field.javaClass.getDeclaredField("kernels").apply { isAccessible=true }.get(field) as Map<*,*>
            return kernels.isNotEmpty() && kernels.values.all { kernel ->
                val value=requireNotNull(kernel)
                prepared(value.javaClass.getDeclaredField("shadow").apply { isAccessible=true }.get(value) as RetainedGlassShadow)
            }
        }
        val sources = mutableListOf<FrostedBackdrop>()
        val background = if (glow) Color(.18f,.18f,.18f) else Color(.82f,.82f,.82f)
        val style = LiftShadow(1f,1f,if(glow) Color.White else Color.Black)
        @Composable fun panel(mode: Int) {
            val source = rememberFrostedBackdrop(Unit, dark = glow)
            BackdropLease(source)
            DisposableEffect(source) { sources+=source; onDispose { sources-=source } }
            val graphics = LocalGraphicsContext.current
            val scope = rememberCoroutineScope()
            val a = remember { HoverGroupRenderer(graphics,scope) }
            val b = remember { HoverGroupRenderer(graphics,scope) }
            val shadowA = remember { HoverGroupRenderer(graphics,scope) }
            val shadowB = remember { HoverGroupRenderer(graphics,scope) }
            DisposableEffect(a,b,shadowA,shadowB) { onDispose { listOf(a,b,shadowA,shadowB).forEach { it.release() } } }
            val plane = rememberShadowPlane()
            val sourceField = rememberNativePathField(plane,Unit,if(glow) ShadowRole.Glow else ShadowRole.Lift)
            val field = sourceField.takeIf { mode==1 }
            SideEffect { ready=when(mode) { 0 -> { { groupReady(a) && groupReady(b) } }; 1 -> { { fieldReady(sourceField) } }; else -> { { groupReady(shadowA) && groupReady(shadowB) } } } }
            val location = remember { BackdropLocation() }
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            Box(Modifier.width(320.dp).height(180.dp).testTag("optics").backdropLocation(location)
                .drawWithContent {
                    drawContent()
                    field?.beginFrame()
                    try {
                        val d=density.density
                        fun part(left: Float,top: Float,right: Float,bottom: Float)=hoverGeometry(
                            MediaItemOutline(Rect(left*d,top*d,right*d,bottom*d),12*d),Rect(3*d,3*d,3*d,3*d),1f)
                        val first=listOf(part(58f,28f,130f,100f),part(134f,28f,206f,100f))
                        val cross=listOf(part(100f,92f,172f,144f))
                        val opacity=listOf(1f,alpha)
                        fun paint(group: HoverGroupRenderer,parts: List<HoverGeometry>,items: List<Float>,backing: FrostedBackdrop?,shadow: Boolean) {
                            drawHoverGroup(group,parts,{ _,_->1f },backing,location,Build.VERSION.SDK_INT>=33,
                                density,direction,16*d,shadow=shadow,itemOpacity=items,splitRestComponents=true,
                                lift=style,pathField=field)
                        }
                        paint(a,first,opacity,source,mode!=2)
                        paint(b,cross,listOf(.5f),source,mode!=2)
                        // Foreground pixels are part of the source, before any illegally deferred field.
                        drawRect(Color.Magenta,Offset(120*d,96*d),Size(8*d,8*d))
                        if(mode==2) {
                            paint(shadowA,first,opacity,null,true)
                            paint(shadowB,cross,listOf(.5f),null,true)
                        }
                    } finally { field?.endFrame() }
                }) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(background) }
            }
        }
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(320.dp,180.dp).background(background)) { panel(mode) }
        } }
        compose.waitUntil(5000) { sources.size==1 && sources.all { it.recorded && (Build.VERSION.SDK_INT>=33 || it.snapshot!=null) } }
        val output=nativeEvidenceDirectory("selection-plane-optics-${BuildConfig.SOURCE_SHA.take(8)}",File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"selection-plane-optics")).apply { mkdirs() }
        val report=StringBuilder("glow,alpha,inlineMax,splitMax,oldControlMax\n")
        var worst=0
        var sensitivity=0
        var controlWorst=0
        for((index,value) in listOf(0f,.5f,1f,.5f,0f).withIndex()) {
            compose.runOnIdle { alpha=value }
            // Compare each renderer at the SAME physical origin; displaced native saveLayers
            // can quantize fractional fields differently even with identical drawing code.
            val images=listOf(0,1,2,0).map { next ->
                compose.runOnIdle { mode=next }
                // Publish each requested contour, then wait for BOTH renderers
                // to use their actual retained raster rather than compare live vs cached.
                compose.onNodeWithTag("optics").captureNativeFrame()
                compose.mainClock.advanceTimeBy(64)
                compose.waitUntil(5000) { ready() }
                compose.onNodeWithTag("optics").captureNativeFrame()
            }
            val pixels=images.map { it.toPixelMap() }
            fun maximum(other: Int): Int {
                var max=0
                for(y in 0 until pixels[0].height) for(x in 0 until pixels[0].width) {
                    val a=pixels[0][x,y]; val b=pixels[other][x,y]
                    max=maxOf(max,(maxOf(abs(a.red-b.red),abs(a.green-b.green),abs(a.blue-b.blue))*255).roundToInt())
                }
                return max
            }
            val inline=maximum(1); val split=maximum(2); val control=maximum(3)
            controlWorst=maxOf(controlWorst,control)
            report.append("$glow,$value,$inline,$split,$control\n")
            worst=maxOf(worst,inline); sensitivity=maxOf(sensitivity,split)
            images.forEachIndexed { mode,image -> File(output,"${if(glow) "glow" else "lift"}-$index-$mode.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        }
        File(output,"${if(glow) "glow" else "lift"}.csv").writeText(report.toString())
        assertEquals("The unchanged renderer must reproduce the same origin and cache state",0,controlWorst)
        assertEquals("Inline plane dispatch must preserve the legacy composite byte for byte",0,worst)
        assertTrue("The witness must reject moving the field after its body and icons: splitMax=$sensitivity",sensitivity>2)
    }
}
