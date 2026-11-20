package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.glassShadowInset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import android.view.View
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.*

/** The canonical field is projected into physical host coordinates. Shared source
 * fields deform the body and keep ring widths in device pixels; private fields
 * inherit their control's draw transform. The same distinction survives transfer. */
internal class NativeSurfaceField(private val plane: ShadowPlane,private val node: SurfaceNode,private val cache: RetainedGlassShadow,private val spread: Float) {
    private var frame=ShapeFrame(emptyList(),Rect.Zero,0f,GlassStrain.None)
    internal fun frame()=frame
    fun draw(scope: androidx.compose.ui.graphics.drawscope.ContentDrawScope,opacity: Float,strength: Float,radius: Float,
        sx: Float,sy: Float,shift: Float,receiver: GlassShadowReceiver?,exclusion: GlassShadowExclusion?,
        outline: List<RoundRect>?,localOrigin: ()->Offset?,content: ()->Unit) = with(scope) {
        val corner=radius.coerceIn(0f,size.minDimension/2f)
        val presented=Rect(Offset(shift+size.width*(1f-sx)/2f,size.height*(1f-sy)/2f),Size(size.width*sx,size.height*sy))
        val origin=outline?.let { localOrigin() }
        val bodies=if(outline==null || origin==null) listOf(RoundRect(presented,CornerRadius(corner*sx,corner*sy))) else outline.map { body ->
            val topLeft=Offset(presented.left+(body.left-origin.x)*sx,presented.top+(body.top-origin.y)*sy)
            RoundRect(Rect(topLeft,Size(body.width*sx,body.height*sy)),CornerRadius(body.topLeftCornerRadius.x*sx,body.topLeftCornerRadius.y*sy))
        }
        val footprint=if(bodies.size==1) presented else Rect(bodies.minOf { it.left },bodies.minOf { it.top },bodies.maxOf { it.right },bodies.maxOf { it.bottom })
        frame=ShapeFrame(bodies,footprint,corner,GlassStrain.None)
        val alpha=opacity.coerceIn(0f,1f)
        if(alpha<=0f) return@with
        val scaled=node.fieldInheritsBodyScale
        val pad=glassShadowInset(spread).toPx()+2f
        val layer=alpha<1f
        if(layer) {
            val padded=Rect(footprint.left-pad*(if(scaled) sx else 1f),footprint.top-pad*(if(scaled) sy else 1f),
                footprint.right+pad*(if(scaled) sx else 1f),footprint.bottom+pad*(if(scaled) sy else 1f))
            drawContext.canvas.saveLayer(padded,Paint().apply { this.alpha=alpha })
        }
        try {
            if(node.owns(plane)) {
                if(scaled && bodies.size==1) translate(left=shift) { scale(sx,sy) {
                    with(cache) { draw(listOf(RoundRect(Rect(Offset.Zero,size),CornerRadius(corner)) to 1f),strength,spread,receiver,exclusion) }
                } } else with(cache) { draw(node.frameOn(plane).bodies.map { it to 1f },strength,spread,receiver,exclusion) }
            }
            content()
        } finally { if(layer) drawContext.canvas.restore() }
    }
}

@Composable internal fun rememberNativeSurfaceField(plane: ShadowPlane,surface: SurfaceView,spread: Float): NativeSurfaceField {
    val node=surface as SurfaceNode
    val cache=rememberGlassShadow()
    val value=remember(plane,node,cache,spread) { NativeSurfaceField(plane,node,cache,spread) }
    val view=LocalView.current
    SideEffect { plane.view=view }
    DisposableEffect(plane,node,value) { node.project(plane,value::frame); onDispose { node.project(plane,null) } }
    return value
}

/** A detached stock panel has no physical source caster to borrow. */
@Composable internal fun rememberWindowCaster(plane: ShadowPlane,key: Any): SurfaceView {
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

/** Platform adapter intents accept the read-only identity; mutation stays Engine-private. */
internal fun SurfaceView.windowRetained()=(this as SurfaceNode).windowRetained
internal fun SurfaceView.retainNativeWindow()=(this as SurfaceNode).retainWindow()
private fun View.detachments()=callbackFlow {
    val listener=object: View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { trySend(Unit) }
    }
    addOnAttachStateChangeListener(listener); if(!isAttachedToWindow) trySend(Unit)
    awaitClose { removeOnAttachStateChangeListener(listener) }
}
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun SurfaceView.followWindow(motion: PopupWindowMotion) {
    val node=this as SurfaceNode; val bounds=requireNotNull(motion.bounds)
    merge(snapshotFlow { node.canFollow(motion.expansion,bounds) }.map { Unit },
        snapshotFlow { node.staleWindows() }.flatMapLatest { views -> merge(*views.map { it.detachments() }.toTypedArray()) }).first { node.canFollow(motion.expansion,bounds) }
    node.follow(motion.expansion,Curve.constant(1f),bounds,motion.popupCurve)
}
internal fun SurfaceView.transferWindow(plane: ShadowPlane)=(this as SurfaceNode).transfer(plane,motionProfileId(io.github.anastaci1a.localica.ui.spec.MotionProfile.Control))
internal fun SurfaceView.ownsWindow(plane: ShadowPlane)=(this as SurfaceNode).owns(plane)
internal fun SurfaceView.returnPlane()=(this as SurfaceNode).sourceReturnPlane
/** A retained but undrawn origin cannot acknowledge restoration. Keep the native
 * receipts on the normal path, but retire after the base's six-frame allowance.
 * Bound the field return too: a hidden home cannot submit its owned field. */
internal suspend fun awaitTriggerReturn(returnHome: suspend () -> Unit) = coroutineScope {
    val returned = async(start = CoroutineStart.UNDISPATCHED) { returnHome() }
    val deadline = async { repeat(6) { withFrameNanos { } } }
    try {
        select<Unit> {
            returned.onAwait { }
            deadline.onAwait { }
        }
    } finally {
        returned.cancel()
        deadline.cancel()
    }
}

/** One presented frame in which the acknowledged trigger draws while the modal still owns
 * the window: a reopen there reclaims the same dialog instead of disposing it (#858). */
internal suspend fun awaitReturnedPresentation() { withFrameNanos { } }

/** The destination must submit its owned field after transfer, before the native host is released. */
internal suspend fun SurfaceView.awaitWindowField(plane: ShadowPlane): Boolean {
    val node=this as SurfaceNode
    val request=node.requestOwnedField(plane)
    snapshotFlow { !node.currentFieldRequest(request) || request.submitted }.first { it }
    return node.currentFieldRequest(request) && request.submitted
}

internal fun popupContentScale(width: Int,height: Int,contentWidth: Int,contentHeight: Int,fixed: Boolean)=
    if(fixed) 1f else minOf(width.toFloat()/contentWidth.coerceAtLeast(1),height.toFloat()/contentHeight.coerceAtLeast(1)).coerceAtMost(1f)
internal fun androidx.compose.ui.layout.Placeable.PlacementScope.placePopupFace(face: androidx.compose.ui.layout.Placeable,x: Int,y: Int,phase: ()->Float) {
    if(phase()<.55f) face.place(x,y)
}

internal fun nativeShadowExclusion(bodies: List<RoundRect>,origin: Offset,size: Size,scaleX: Float,scaleY: Float,shift: Float,weight: Float)=
    if(bodies.isEmpty()) null else GlassShadowExclusion(bodies,origin,size,scaleX,scaleY,shift,weight)

internal fun SurfaceView.fieldScalesWithBody()=(this as SurfaceNode).fieldInheritsBodyScale
