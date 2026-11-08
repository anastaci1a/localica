package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView

/** Draw the actual transferred caster at the presenter's existing field boundary.
 * The projection is host-local geometry of the same shape, never a second node. */
@Composable internal fun Modifier.nativeSurfaceShadow(
    plane: ShadowPlane, node: SurfaceNode?, strength: ()->Float, spread: Float, radius: ()->Float,
    receiver: ()->GlassShadowReceiver?, exclusion: ()->GlassShadowExclusion?
): Modifier {
    val cache=rememberGlassShadow()
    val view=LocalView.current
    val size=remember { arrayOf(Size.Zero) }
    val latestRadius by rememberUpdatedState(radius)
    DisposableEffect(node,plane) {
        node?.project(plane) {
            val bounds=Rect(Offset.Zero,size[0])
            val body=RoundRect(bounds,CornerRadius(latestRadius().coerceIn(0f,size[0].minDimension/2f)))
            ShapeFrame(listOf(body),bounds,body.topLeftCornerRadius.x,GlassStrain.None)
        }
        onDispose { node?.project(plane,null) }
    }
    SideEffect { plane.view=view }
    return onPlaced { plane.layout=it }.drawBehind {
        size[0]=this.size
        plane.revision
        if(node?.owns(plane)==true) with(cache) {
            draw(node.frameOn(plane).bodies.map { it to 1f },strength(),spread,receiver(),exclusion())
        }
        if(plane.receipt()) view.postOnAnimation { plane.admit() }
    }
}

/** A detached stock panel has no physical source caster to borrow. */
@Composable internal fun rememberWindowCaster(plane: ShadowPlane,key: Any): SurfaceNode {
    val clock=ownerClock()
    val node=remember(clock,plane,key) { SurfaceNode(clock,key,LayoutShape(key,plane),SurfaceTarget(Stratum.Modal,1f,ShadowRole.Glass),plane) }
    DisposableEffect(node) { onDispose { node.dispose() } }
    return node
}

/** Native draw receipt outside the field's admission/opacity gate. */
internal fun Modifier.nativePlaneReceipt(plane: ShadowPlane): Modifier = this.drawWithContent {
    plane.revision
    drawContent()
    if(plane.receipt()) plane.view?.postOnAnimation { plane.admit() }
}
