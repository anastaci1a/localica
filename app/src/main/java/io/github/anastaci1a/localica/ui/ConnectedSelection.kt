package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.engine.*
import kotlin.math.*

/** [corner] comes from the item's shared outline (#532); null keeps the generic contour. */
internal data class SelectionRegion(val bounds: Rect, val corner: Float? = null) {
    fun radius(gap: Float): Float = corner?.let { minOf(it, bounds.width / 2f, bounds.height / 2f).coerceAtLeast(0f) }
        ?: mediaItemCornerRadius(bounds, gap)
    fun contour(gap: Float) = androidx.compose.ui.geometry.RoundRect(bounds, CornerRadius(radius(gap)))
}

internal class SelectionEntry {
    var coordinates: LayoutCoordinates? = null
    var itemKey: Any? = null
    var selected by mutableStateOf(false)
    /** Eased hover progress (#533); the name is kept for the selection inspection tests. */
    var visibility: () -> Float by mutableStateOf({ 0f })
    var visualSize by mutableStateOf<(Size) -> Size>({ it })
    var outline by mutableStateOf<((Size, LayoutDirection) -> MediaItemOutline?)?>(null)
    var direction by mutableStateOf(LayoutDirection.Ltr)
    /** The item's own drawing while it hovers; the host draws it lifted. */
    var layer: GraphicsLayer? = null
    /** The cell's own fades (entry sweep, drag handoff) that its in-place drawing would get. */
    var alpha: () -> Float = { 1f }
    var recorded = false
    /** This item's last drawn lift in its own coordinates while it hovers; null at rest (#554). */
    var presented: HoverGeometry? = null
}

@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionEntry>()
    var placement by mutableIntStateOf(0)
    // Last geometry actually submitted by the host draw, not copied placement callbacks.
    // Plain state for source/native regression inspection; never drives composition or animation.
    var lastDrawnRegions: List<SelectionRegion> = emptyList()
    /** Each hovering item's last drawn lift, in its own coordinates, by item key. A drag
     * starts its cards from exactly this geometry. Plain state, written while drawing. */
    val hover = mutableMapOf<Any, HoverGeometry>()
    val viewportHover = mutableMapOf<Any, HoverGeometry>()
    /** A surrounding geometry transition resolves the same transform for all content passes. */
    var geometryResolver: ((Any?, HoverGeometry) -> HoverGeometry)? = null
    /** Virtualized pinch items join live bodies under the same surface and shadow. */
    var retainedHover: List<Pair<HoverGeometry, Float>> = emptyList()
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Lets a cell's other decorations (the current-occurrence frost) follow its hover. */
@Stable internal class SelectionHoverHandle {
    var progress: () -> Float = { 0f }
}

/** Scrolling content and its hovering selections share a single clip, fade and overscroll transform. */
@Composable
internal fun SelectionViewport(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, visibleItemKeys: (() -> Set<Any>)? = null,
    scene: SelectionScene = remember { SelectionScene() }, liftEdges: LiftEdges = LiftEdges.Horizontal,
    content: @Composable (OverscrollEffect?) -> Unit) {
    val overscroll = rememberOverscrollEffect()
    val events = remember(overscroll) { overscroll?.withoutVisualEffect() }
    ConnectedSelectionHost(modifier.clipToBounds().then(if (overscroll != null) Modifier.overscroll(overscroll) else Modifier),
        contentDrawing = contentDrawing, observePlacement = observePlacement, visibleItemKeys = visibleItemKeys,
        scene = scene, liftEdges = liftEdges) {
        content(events)
    }
}

/**
 * Hover-lift selection (#533). Selected items rise: their outline grows into a share of the
 * gap, a shadow deepens, and the surface shows the same backdrop region magnified with a
 * slight frost. Side-adjacent hovering items share one outline. Items draw their own content
 * into a retained layer while hovering; this host draws it above the shadow and surface.
 */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, visibleItemKeys: (() -> Set<Any>)? = null,
    scene: SelectionScene = remember { SelectionScene() }, liftEdges: LiftEdges = LiftEdges.Horizontal,
    content: @Composable () -> Unit) {
    val flatColor = if (flatGlass()) flatGlassColor() else null
    val liftStyle = rememberLiftShadow()
    val averageProgress = glassAverageProgress()
    // Older Android uses the shared solid-average path.
    val blurEffects = Build.VERSION.SDK_INT >= 33
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableListOf<HoverGroupRenderer>() }
    DisposableEffect(graphics) { onDispose { groups.forEach { it.release() }; groups.clear() } }
    val backing = LocalFrostedBackdrop.current
    val hasSelection by remember { derivedStateOf { scene.regions.values.any { it.selected || it.visibility() > 0f } } }
    BackdropLease(backing.takeIf { hasSelection })
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val location = remember { BackdropLocation() }
    val recordingDensity = LocalDensity.current
    val recordingDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.then(contentDrawing).backdropLocation(location).onPlaced { coordinates = it }.drawWithContent {
            // Pager placement also moves unselected rows. Do not subscribe their
            // otherwise reusable display lists to the selection geometry clock.
            if (!hasSelection && scene.retainedHover.isEmpty()) {
                groups.forEach { it.release() }
                scene.lastDrawnRegions = emptyList()
                scene.hover.clear()
                scene.viewportHover.clear()
                return@drawWithContent drawContent()
            }
            // Lazy scrolling can update retained child layers without replacing LayoutCoordinates.
            // Subscribe THIS drawing to its owner's layout result, then read live coordinates.
            observePlacement()
            scene.placement
            // Hovering items skip their in-place drawing; everything else is unchanged.
            drawContent()
            val host = coordinates ?: return@drawWithContent
            // Lazy prefetch may keep an unplaced item's coordinates attached.
            // Its owner's public native keys, rather than attachment, admit it.
            val placedKeys = visibleItemKeys?.invoke()
            class Placed(val entry: SelectionEntry, val at: Offset, val rest: MediaItemOutline, val progress: Float)
            val placed = scene.regions.values.mapNotNull { entry ->
                val item = entry.coordinates
                if (item == null || !item.isAttached || placedKeys != null && entry.itemKey !in placedKeys) return@mapNotNull null
                val at = host.localPositionOf(item, Offset.Zero)
                val measured = Size(item.size.width.toFloat(), item.size.height.toFloat())
                val outline = entry.outline?.invoke(measured, entry.direction)
                    ?: mediaItemBounds(measured, entry.visualSize(measured), entry.direction).let {
                        MediaItemOutline(it, SelectionRegion(it).radius(MediaSelectionGap.toPx()))
                    }
                Placed(entry, at, outline.translate(at), entry.visibility().coerceIn(0f, 1f))
            }
            // Selection intent can arrive one frame before lift progress. Only a visible lift
            // contributes glass; zero-progress content still belongs in its resting position.
            val hovering = placed.filter { it.progress > 0f && it.entry.recorded && it.entry.layer?.isReleased == false }
            val hoveringKeys = hovering.mapNotNullTo(mutableSetOf()) { it.entry.itemKey }
            scene.hover.keys.retainAll(hoveringKeys)
            scene.viewportHover.keys.retainAll(hoveringKeys)
            if (hovering.isEmpty() && scene.retainedHover.isEmpty()) { scene.lastDrawnRegions = emptyList(); return@drawWithContent }
            val viewport = Rect(Offset.Zero, size)
            val cap = HoverLift.MaxLift.toPx()
            val others = placed.map { it.rest.bounds } + scene.retainedHover.map { it.first.rest.bounds }
            val liveLifts = hovering.map { item ->
                val current = hoverGeometry(item.rest, hoverRoom(item.rest.bounds, others, viewport, liftEdges, cap,
                    neighbourInset = MediaOutlinePadding.toPx(), clearance = HoverLift.Clearance.toPx()), item.progress)
                scene.geometryResolver?.invoke(item.entry.itemKey, current) ?: current
            }
            hovering.forEachIndexed { index, item ->
                item.entry.presented = liveLifts[index].translate(-item.at)
                item.entry.itemKey?.let { scene.hover[it] = liveLifts[index].translate(-item.at); scene.viewportHover[it] = liveLifts[index] }
            }
            val lifts = liveLifts + scene.retainedHover.map { it.first }
            val opacities = hovering.map { it.entry.alpha().coerceIn(0f, 1f) } + scene.retainedHover.map { it.second }
            scene.lastDrawnRegions = lifts.map { SelectionRegion(it.lifted.bounds, it.lifted.corner) }
            val gap = MediaSelectionGap.toPx()
            // Groups join by REST geometry, so growth never changes membership mid-motion.
            val components = hoverComponents(lifts.map { it.rest.bounds }, gap)
            while (groups.size < components.size) groups += HoverGroupRenderer(graphics, scope)
            for(index in components.size until groups.size)groups[index].release()
            components.forEachIndexed { index, members ->
                val group = groups[index]
                val orderedMembers=members.sorted()
                val parts = orderedMembers.map { lifts[it] }
                val strengths = { a: Int, b: Int -> min(parts[a].progress, parts[b].progress) }
                val opacity = members.maxOf { opacities[it] }
                drawHoverGroup(group, parts, strengths, backing, location, blurEffects, recordingDensity, recordingDirection, gap, opacity = opacity, itemOpacity = orderedMembers.map { opacities[it] }, splitRestComponents = true, lift = liftStyle, averageProgress = averageProgress.value, flatColor = flatColor)
                members.forEach { member ->
                    val item = hovering.getOrNull(member) ?: return@forEach
                    val lift = lifts[member]
                    val layer = item.entry.layer ?: return@forEach
                    translate(item.at.x, item.at.y) {
                        val pivot = lift.pivot - item.at
                        layer.alpha = item.entry.alpha().coerceIn(0f, 1f)
                        scale(lift.scale, lift.scale, pivot) { drawLayer(layer) }
                    }
                }
            }
        }) { content() }
    }
}

/** One surface's retained blur/shadow resources; each active subset has its own recording. */
internal class HoverSurfaceRenderer(private val graphics: GraphicsContext,scope: kotlinx.coroutines.CoroutineScope, val flat: Boolean = false) {
    private val opticalLayer = if (flat) null else graphics.createGraphicsLayer().also { GlassWork.event("layer") }
    private val averageLayer = if (flat) null else graphics.createGraphicsLayer().also { GlassWork.event("layer") }
    val output get() = requireNotNull(opticalLayer)
    val flatOutput get() = requireNotNull(averageLayer)
    val average = if (!flat && Build.VERSION.SDK_INT >= 33) MaterialAverageRenderer() else null
    private val lifetime=kotlinx.coroutines.Job(scope.coroutineContext[kotlinx.coroutines.Job])
    val shadow=RetainedGlassShadow(kotlinx.coroutines.CoroutineScope(scope.coroutineContext+lifetime))
    /** Root adapter for callers outside the D04 allowance (HoverDrag): the same retained renderer call. */
    fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadow(bodies: List<Pair<androidx.compose.ui.geometry.RoundRect,Float>>,strength: Float,spread: Float,color: androidx.compose.ui.graphics.Color)=with(shadow) { draw(bodies,strength,spread,color=color) }
    fun release() { lifetime.cancel();opticalLayer?.let(graphics::releaseGraphicsLayer);averageLayer?.let(graphics::releaseGraphicsLayer) }
}

internal class HoverGroupRenderer(private val graphics: GraphicsContext,private val scope: kotlinx.coroutines.CoroutineScope) {
    val outlines=SelectionOpacityPathCache()
    private val surfaces=mutableMapOf<List<Int>,HoverSurfaceRenderer>()
    fun surface(members: List<Int>, flat: Boolean = false): HoverSurfaceRenderer {
        surfaces[members]?.takeIf { it.flat != flat }?.let { it.release(); surfaces.remove(members) }
        return surfaces.getOrPut(members) { HoverSurfaceRenderer(graphics,scope,flat) }
    }
    fun retain(members: Set<List<Int>>) {
        val unused=surfaces.keys.filter { it !in members }
        unused.forEach { surfaces.remove(it)?.release() }
    }
    fun release() { surfaces.values.forEach { it.release() };surfaces.clear();outlines.clear() }
}

/** Premultiplied opacity slices expose persistent shadows continuously as neighbours fade. */
internal fun DrawScope.drawHoverGroup(group: HoverGroupRenderer, parts: List<HoverGeometry>, strength: (Int, Int) -> Float,
    backing: FrostedBackdrop?, location: BackdropLocation, blurEffects: Boolean,
    density: androidx.compose.ui.unit.Density, direction: LayoutDirection, gap: Float,
    sampleOffset: Offset = Offset.Zero, shadow: Boolean = true, opacity: Float = 1f,
    itemOpacity: List<Float>? = null, splitRestComponents: Boolean = false, lift: LiftShadow, averageProgress: Float = 0f, flatColor: Color? = null) {
    if(parts.isEmpty()) { group.release();return }
    val progress=parts.maxOf { it.progress }*opacity.coerceIn(0f,1f)
    if(progress<=0f) { group.release();return }
    val visibility=parts.mapIndexed { i,part ->
        (part.progress*(itemOpacity?.get(i) ?: opacity).coerceIn(0f,1f)/progress).coerceIn(0f,1f)
    }
    val regions=parts.map { SelectionRegion(it.lifted.bounds,it.lifted.corner) }
    val rest=parts.map { SelectionRegion(it.rest.bounds,it.rest.corner) }
    val layers=group.outlines.paths(regions,rest,gap,visibility,splitRestComponents,strength)
    group.retain(layers.flatMap { layer -> layer.groups.map { it.members } }.toSet())
    if(layers.isEmpty())return
    fun DrawScope.paint(layer: SelectionOutlineLayer) {
        for(outline in layer.groups) drawHoverSurface(group.surface(outline.members, flatColor != null),outline.members.map { parts[it] },
            outline,progress,backing,location,blurEffects,density,direction,sampleOffset,shadow,lift,averageProgress,flatColor)
    }
    if(layers.size==1 && layers[0].weight==1f) { paint(layers[0]);return }
    val padding=if(shadow)(6f+lift.spread*28f).dp.toPx()+2f else gap/2f
    val bounds=regions.map { it.bounds }.reduce { a,b -> a.union(b) }.inflate(padding)
    val canvas=drawContext.canvas
    canvas.saveLayer(bounds,Paint())
    for(layer in layers) {
        canvas.saveLayer(bounds,Paint().apply { alpha=layer.weight;blendMode=BlendMode.Plus })
        paint(layer)
        canvas.restore()
    }
    canvas.restore()
}

/** Every slice uses one analytic path for both its frost mask and shadow caster. */
private fun DrawScope.drawHoverSurface(surface: HoverSurfaceRenderer,parts: List<HoverGeometry>,
    outline: SelectionOutlineGroup,progress: Float,backing: FrostedBackdrop?,location: BackdropLocation,
    blurEffects: Boolean,density: androidx.compose.ui.unit.Density,direction: LayoutDirection,
    sampleOffset: Offset,shadow: Boolean,lift: LiftShadow,averageProgress: Float,flatColor: Color?) {
    val path=outline.path
    val rest=parts.map { SelectionRegion(it.rest.bounds,it.rest.corner) }
    if(shadow)with(surface.shadow) {
        draw(outline.localPath,outline.origin,lift.strength*progress,lift.spread,color=lift.color)
    }
    if (flatColor != null) { drawPath(path, flatColor, alpha = progress); return }
    val source = backing?.takeIf { it.recorded && !it.layer.isReleased } ?: return
    if (blurEffects) location.observeRecordingAcrossWindows(source)
    val registration = source.location.registrationTo(location) ?: return
    val restBounds = rest.map { it.bounds }.reduce { a, b -> a.union(b) }
    val liftedBounds = parts.map { it.lifted.bounds }.reduce { a, b -> a.union(b) }
    val (scale, translation) = hoverMagnification(restBounds, liftedBounds)
    fun DrawScope.magnified(draw: DrawScope.() -> Unit) = withTransform({
        translate(translation.x, translation.y); scale(scale.x, scale.y, Offset.Zero)
        translate(sampleOffset.x, sampleOffset.y)
    }) { withBackdropRegistration(registration, draw) }
    val blur = HoverLift.Blur.toPx() * progress
    clipPath(path) {
        val blending = averageProgress > 0f && averageProgress < 1f
        if (blending) drawContext.canvas.saveLayer(path.getBounds(), Paint())
        if (blurEffects && averageProgress < 1f && blur > .5f) {
            // Blur a crop larger than the outline, then clip: edges sample real backdrop.
            val crop = path.getBounds().inflate(blur * 2f).intersect(Rect(Offset.Zero, size))
            if (!crop.isEmpty) {
                val output = surface.output
                output.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                output.record(density, direction, IntSize(ceil(crop.width).toInt(), ceil(crop.height).toInt())) {
                    translate(-crop.left, -crop.top) { magnified { drawLayer(source.layer) } }
                }
                translate(crop.left, crop.top) { drawLayer(output.also { it.alpha = progress * (1f - averageProgress) }) }
            }
        } else if (!blurEffects) {
            val average = source.averageColor(location, path.getBounds())
            if (average != null) drawPath(path, average, alpha = progress)
        } else if (averageProgress < 1f) {
            drawContext.canvas.saveLayer(path.getBounds(), Paint().apply { alpha = progress * (1f - averageProgress) })
            magnified { drawLayer(source.layer) }
            drawContext.canvas.restore()
        }
        if (blurEffects && averageProgress > 0f && Build.VERSION.SDK_INT >= 33) {
            val crop = path.getBounds()
            val output = surface.flatOutput
            output.renderEffect = surface.average?.effect()
            output.record(density, direction, IntSize(8, 8)) {
                scale(8f / crop.width, 8f / crop.height, Offset.Zero) {
                    translate(-crop.left, -crop.top) { magnified { drawLayer(source.layer) } }
                }
            }
            output.alpha = progress * averageProgress
            output.blendMode = if (blending) BlendMode.Plus else BlendMode.SrcOver
            translate(crop.left, crop.top) {
                scale(crop.width / 8f, crop.height / 8f, Offset.Zero) { drawLayer(output) }
            }
        }
        if (blending) drawContext.canvas.restore()
    }
}

private fun Rect.union(other: Rect) = Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

/** Rebuild the merged outline only when its relative geometry or bridge strengths change. */
internal class HoverPathCache(profile: SelectionOutlineProfile? = null) {
    private var previousRegions: List<SelectionRegion>? = null
    private var previousGap = Float.NaN
    private var previousStrengths = FloatArray(0)
    private var nextStrengths = FloatArray(0)
    private var origin = Offset(Float.NaN,Float.NaN)
    private var presented = Path()
    private val contour = AnalyticSelectionOutline(profile)
    var localPath = Path(); private set
    var rebuilds = 0; private set
    fun path(regions: List<SelectionRegion>, rest: List<SelectionRegion>, gap: Float, strength: (Int, Int) -> Float): Path {
        if (regions.isEmpty()) return Path()
        val at = regions.first().bounds.topLeft
        val local = localLensRegions(regions, at, previousRegions)
        val count=regions.size
        val pairCount=count*(count-1)/2
        if(nextStrengths.size!=pairCount)nextStrengths=FloatArray(pairCount)
        var changed=local!=previousRegions || gap.toBits()!=previousGap.toBits() || previousStrengths.size!=pairCount
        var index=0
        for(a in regions.indices)for(b in a+1 until count) {
            val value=filletStrength(local[a].bounds,local[b].bounds,gap,strength(a,b))
            nextStrengths[index]=value
            if(index>=previousStrengths.size || value.toBits()!=previousStrengths[index].toBits())changed=true
            index++
        }
        if (changed) {
            previousRegions=local;previousGap=gap
            val old=previousStrengths;previousStrengths=nextStrengths;nextStrengths=old
            rebuilds++
            // Capture each pair once; geometry and its cache key use the same snapshot.
            localPath = connectedSelectionPath(local, gap, strength = { a,b ->
                val lo=min(a,b);val hi=max(a,b)
                previousStrengths[lo*(2*count-lo-1)/2+hi-lo-1]
            }, contour = contour)
        }
        if (changed || at != origin) {
            origin = at
            presented = Path().apply { addPath(localPath, at) }
        }
        return presented
    }
}

/** The renderer and benchmark share this cache, including all mixed-opacity path builds. */
internal class SelectionOpacityPathCache(private val profile: SelectionOutlineProfile?=null) {
    private val caches=mutableMapOf<List<Int>,HoverPathCache>()
    var rebuilds=0;private set
    fun clear() { caches.clear() }
    fun paths(regions: List<SelectionRegion>,rest: List<SelectionRegion>,gap: Float,
        visibility: List<Float>,splitRestComponents: Boolean=false,strength: (Int,Int)->Float): List<SelectionOutlineLayer> {
        require(regions.size==rest.size && regions.size==visibility.size)
        val active=mutableSetOf<List<Int>>()
        val result=selectionOpacitySlices(visibility).map { slice ->
            val components=if(splitRestComponents)hoverComponents(slice.members.map { rest[it].bounds },gap)
                .map { component -> component.map { slice.members[it] } } else listOf(slice.members)
            SelectionOutlineLayer(slice.weight,components.map { members ->
                active+=members
                val cache=caches.getOrPut(members) { HoverPathCache(profile) }
                val pieces=members.map { regions[it] }
                val before=cache.rebuilds
                val path=cache.path(pieces,pieces,gap) { a,b -> strength(members[a],members[b]) }
                rebuilds+=cache.rebuilds-before
                SelectionOutlineGroup(members,path,cache.localPath,pieces.first().bounds.topLeft)
            })
        }
        caches.keys.retainAll(active)
        return result
    }
}

/** Groups by rest geometry: side-adjacent within [gap]; diagonal neighbours stay separate. */
internal fun hoverComponents(rest: List<Rect>, gap: Float): List<List<Int>> {
    val remaining = rest.indices.toMutableList()
    val result = mutableListOf<List<Int>>()
    while (remaining.isNotEmpty()) {
        val group = mutableListOf(remaining.removeAt(0))
        var next = 0
        while (next < group.size) {
            val a = rest[group[next++]]
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val index = iterator.next()
                val b = rest[index]
                val x = max(a.left - b.right, b.left - a.right)
                val y = max(a.top - b.bottom, b.top - a.bottom)
                if (x <= gap && y <= gap && (x < 0 || y < 0)) { group += index; iterator.remove() }
            }
        }
        result += group
    }
    return result
}

/** Optical depth belongs to each connected body, never the hull of separated selections. */
internal fun selectionComponents(regions: List<SelectionRegion>, gap: Float): List<List<SelectionRegion>> =
    hoverComponents(regions.map { it.bounds }, gap).map { group -> group.map { regions[it] } }

/** One analytic outline, driven by presented geometry and the existing motion progress.
 * [rest] remains an adapter parameter; it never authorizes a tether across a displayed gap. */
internal fun connectedSelectionPath(regions: List<SelectionRegion>, gap: Float,
    rest: List<SelectionRegion> = regions, strength: (Int, Int) -> Float = { _, _ -> 1f },
    contour: AnalyticSelectionOutline = AnalyticSelectionOutline()): Path =
    concaveFilletPath(regions.map { it.contour(gap) },gap,strength,contour)

/**
 * Registers an item with the surrounding host and lifts it while selected (#533). While its
 * hover progress is above zero the item's drawing goes to a retained layer that the host
 * draws scaled above the group's shadow and surface. A [handle] shares its progress with
 * the cell's other decorations (the current-occurrence frost).
 */
@Composable
internal fun Modifier.selectionLens(isSelected: Boolean, partial: Boolean,
    itemKey: Any? = null, outline: ((Size, LayoutDirection) -> MediaItemOutline?)? = null,
    handle: SelectionHoverHandle? = null, alpha: () -> Float = { 1f },
    anchor: GlassAnchor? = null, visualSize: (Size) -> Size = { it }): Modifier {
    val scene = LocalSelectionScene.current
    val direction = LocalLayoutDirection.current
    val token = remember { Any() }
    val entry = remember { SelectionEntry() }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val view = androidx.compose.ui.platform.LocalView.current
    val visibility = androidx.compose.animation.core.animateFloatAsState(if (isSelected && !partial) 1f else 0f,
        hoverMotionSpec(LocalReducedMotion.current), label = "Selection hover")
    SideEffect {
        entry.itemKey = itemKey
        entry.visibility = { visibility.value }
        entry.visualSize = visualSize
        entry.outline = outline
        entry.direction = direction
        entry.layer = layer
        entry.alpha = alpha
        handle?.progress = { visibility.value }
        // Popups homed on this item follow its lift (#554), however the item is keyed.
        anchor?.presentation = { entry.presented?.let { lift -> entry.coordinates?.takeIf { it.isAttached }
            ?.let { GlassLift(lift.scale, it.backdropPosition(view) + lift.pivot) } } }
        val selected = isSelected && !partial
        if (entry.selected != selected) { entry.selected = selected; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 } }
        // Registration is identity-stable; rewrite only a missing or replaced entry.
        if (scene != null && scene.regions[token] !== entry) scene.regions[token] = entry
    }
    DisposableEffect(scene, token) { onDispose { scene?.regions?.remove(token); entry.recorded = false } }
    return semantics {
        this.selected = isSelected && !partial
        if (partial) stateDescription = "Partially selected"
    }.onPlaced {
        entry.coordinates = it
        if (entry.selected || entry.visibility() > 0f) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 }
    }.drawWithContent {
        if (scene == null) { drawContent(); return@drawWithContent }
        val hovering = visibility.value > 0f
        // At rest and while hovering, the content is drawn only through this one retained layer,
        // and the layer always stays in this item's own display list (#574). A RenderNode that is
        // moved between display lists, or left out of every list for a frame, loses its recording;
        // retained children such as artwork layers are then never re-recorded, so the cover
        // vanishes until it next changes. The host adds the lifted drawing; it never takes the layer.
        layer.record { this@drawWithContent.drawContent() }
        entry.recorded = true
        if (!hovering) {
            entry.presented = null
            layer.alpha = 1f
            drawLayer(layer)
            return@drawWithContent
        }
        // The host draws this recording lifted in the same frame. Keep it parented here, outside
        // the item's area, so nothing shows in place: a non-empty clip is still recorded.
        clipRect(-1f, -1f, 0f, 0f) { drawLayer(layer) }
    }
}
