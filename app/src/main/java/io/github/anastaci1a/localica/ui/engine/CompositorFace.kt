package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*

/** The node owns the last complete padded raster and releases it with its lifetime. */
internal class CompositorRecording(private val graphics: GraphicsContext) {
    private var layer: GraphicsLayer?=null
    var lastSize=IntSize.Zero
    fun draw(scope: ContentDrawScope,visible: Boolean,opacity: Float,pad: Int,scale: Float) = with(scope) {
        val image=layer?:if(visible) graphics.createGraphicsLayer().also { layer=it } else return@with
        if(visible) image.record(size=IntSize(size.width.toInt()+pad*2,size.height.toInt()+pad*2)) {
            translate(pad.toFloat(),pad.toFloat()) { this@with.drawContent() }
        }
        if(opacity>0f && image.size.width>0) {
            image.alpha=opacity.coerceIn(0f,1f)
            image.scaleX=scale+(1f-scale)*image.alpha; image.scaleY=image.scaleX
            translate(-pad.toFloat(),-pad.toFloat()) { drawLayer(image) }
        }
    }
    fun dispose() { layer?.let(graphics::releaseGraphicsLayer); layer=null }
}

/** A face has one node, one shape, and the expansion's existing bound rectangle.
 * Logical dismissal unplaces children immediately; retained pixels use that node's opacity. */
@Composable internal fun CompositorFace(visible: Boolean,expansion: ExpansionView,bounds: ()->MotionRectView?,opacity: Curve,
    modifier: Modifier=Modifier,inset: Dp=0.dp,retainSize: Boolean=false,scale: Float=1f,
    onMeasured: (IntSize)->Unit={},content: @Composable ()->Unit) {
    val clock=ownerClock()
    val plane=LocalShadowPlane.current?:rememberShadowPlane()
    val graphics=LocalGraphicsContext.current
    val key=remember { Any() }
    val shape=remember(plane,key) { LayoutShape(key,plane).apply { body={ RoundRect(it,CornerRadius.Zero) } } }
    val recording=remember(graphics,key) { CompositorRecording(graphics) }
    val holder=remember(key) { arrayOfNulls<SurfaceNode>(1) }
    SideEffect { if(!visible) holder[0]?.dismiss() }
    DisposableEffect(key) { onDispose { holder[0]?.dispose()?:recording.dispose() } }
    Layout(modifier=modifier.onPlaced {
        shape.coordinates=it
        val node=holder[0]?:SurfaceNode(clock,key,shape,SurfaceTarget(Stratum.Floating,0f,ShadowRole.None),plane)
            .also { value -> value.recording=recording; value.follow(expansion,opacity,requireNotNull(bounds()) { "Measure the retained face's basis before placement" }); holder[0]=value }
        if(!visible) node.dismiss()
    }.then(if(visible) Modifier else Modifier.clearAndSetSemantics { })
        .then(Modifier.drawWithContent {
            val node=holder[0]?:return@drawWithContent
            if(visible || node.retained) recording.draw(this,visible,node.opacity.value(),inset.roundToPx(),scale)
        }),content={ CompositionLocalProvider(LocalShadowPlane provides plane) { Box { content() } } }) { children,constraints ->
        val child=children.single().measure(constraints)
        if(visible || recording.lastSize==IntSize.Zero) recording.lastSize=IntSize(child.width,child.height)
        val size=if(retainSize && !visible) recording.lastSize else IntSize(child.width,child.height)
        onMeasured(size)
        layout(size.width,size.height) { if(visible) child.place(0,0) }
    }
}
