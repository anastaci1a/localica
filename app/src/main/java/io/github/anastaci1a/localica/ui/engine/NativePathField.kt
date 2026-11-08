package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.*

/** Native adapter for a plane's joined selection field. The caller keeps its original
 * per-slice [field then body] compositing order; this adapter owns the path kernels. */
internal class NativePathField(private val plane: ShadowPlane,private val scope: CoroutineScope,
    override val key: Any): ShapeView {
    private class Kernel(scope: CoroutineScope) {
        val lifetime=Job(scope.coroutineContext[Job])
        val shadow=RetainedGlassShadow(CoroutineScope(scope.coroutineContext+lifetime))
    }
    private val kernels=mutableMapOf<Any,Kernel>()
    private val used=mutableSetOf<Any>()
    private val contours=mutableListOf<Path>()
    private var published: ShapeFrame?=null
    private lateinit var node: SurfaceNode
    internal fun bind(value: SurfaceNode) { node=value }
    fun beginFrame() { contours.clear(); used.clear(); published=null }
    fun draw(scope: DrawScope,key: Any,path: Path,origin: Offset,strength: Float,spread: Float,color: Color) = with(scope) {
        if(!node.owns(plane)) return@with
        require(node.role==ShadowRole.Glow && color==Color.White || node.role==ShadowRole.Lift && color==Color.Black)
        // Published frames own their contour; later cache reuse cannot mutate them.
        contours+=Path().apply { addPath(path,origin) }
        published=null
        used+=key
        val kernel=kernels.getOrPut(key) { Kernel(this@NativePathField.scope) }
        with(kernel.shadow) { draw(path,origin,strength,spread,color) }
    }
    fun endFrame() { kernels.keys.filter { it !in used }.forEach { kernels.remove(it)?.lifetime?.cancel() } }
    override fun outlineAt(stamp: FrameStamp)=outline()
    override fun outline(): ShapeFrame = published ?: run {
        val path=Path().apply { contours.forEach { addPath(it) } }
        ShapeFrame(emptyList(),path.getBounds(),0f,GlassStrain.None,publishedPath=path).also { published=it }
    }
    fun dispose() { kernels.values.forEach { it.lifetime.cancel() }; kernels.clear(); contours.clear(); used.clear(); published=null }
}

@Composable internal fun rememberNativePathField(plane: ShadowPlane,key: Any,role: ShadowRole): NativePathField {
    val clock=ownerClock()
    val scope=rememberCoroutineScope()
    val field=remember(clock,plane,key) { NativePathField(plane,scope,key) }
    val node=remember(clock,plane,field) { SurfaceNode(clock,key,field,SurfaceTarget(Stratum.Lifted,1f,role),plane).also { field.bind(it) } }
    SideEffect { node.adoptPathField(field,role) }
    DisposableEffect(node,field) { onDispose { node.dispose(); field.dispose() } }
    return field
}
