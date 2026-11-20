package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.FluidKickSpec
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import io.github.anastaci1a.localica.ui.spec.PopupOutline
import io.github.anastaci1a.localica.ui.spec.PopupOutlinePreview
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import io.github.anastaci1a.localica.ui.spec.fluidThroughGrows

/** A popup's resting window margin in pixels, exactly v0.34.1's for every motion kind (#854 review): the spring overshoot exists
 * only in flight and may pass this margin at its peak; it never moves or shrinks a resting menu or dialog. Pages fill the window. */
internal fun Density.popupMarginPx(page: Boolean): Float = if (page) 0f else StockUiSpec.space.getValue(Space.Base).toPx()

/** Corners stay between their endpoints while the owning edges retain spring overshoot. */
internal fun popupCornerRadius(home: Dp, popup: Dp, geometry: Float): Dp {
    val g = geometry.coerceIn(0f, 1f)
    return home * (1f - g) + popup * g
}

internal data class PopupPortalBounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX get() = minX + kotlin.math.abs(width) * 0.5
    val midY get() = minY + kotlin.math.abs(height) * 0.5
    private val minX get() = if (width < 0.0) x + width else x
    private val minY get() = if (height < 0.0) y + height else y

    fun standardized() = PopupPortalBounds(minX, minY, kotlin.math.abs(width), kotlin.math.abs(height))

    fun centeredAt(centerX: Double, centerY: Double) =
        copy(x = centerX - width * 0.5, y = centerY - height * 0.5)

    fun maskOffset() = -minX to -minY

    /** Translation input in the scaled portal basis, before composing it with scale. */
    fun cropOffset(croppedMidX: Double, croppedMidY: Double) = (midX - croppedMidX) to (midY - croppedMidY)
}

internal data class PopupPortalInsets(val top: Double, val leading: Double, val bottom: Double, val trailing: Double) {
    val horizontal get() = leading + trailing
    val vertical get() = top + bottom
    val isZero get() = top == 0.0 && leading == 0.0 && bottom == 0.0 && trailing == 0.0

    fun signedCrop(bounds: PopupPortalBounds?): PopupPortalBounds? = bounds?.standardized()?.let {
        PopupPortalBounds(it.x + leading, it.y + top, it.width - horizontal, it.height - vertical)
    }
}

private const val PopupUnclippedContent = 4
internal fun fluidPopupClipsBounds(itemOptions: Int) = (itemOptions and PopupUnclippedContent) == 0
internal fun fluidPopupPreviewOptions(mode: Int, options: Int) =
    if (mode == 4 || mode == 5) options or PopupUnclippedContent else options
internal fun fluidPopupVisibleMask(pathBounds: PopupPortalBounds?, roundedRect: Boolean, equalsReferenceBounds: Boolean) =
    pathBounds?.takeIf { !roundedRect || !equalsReferenceBounds }

internal fun fluidPopupContentBounds(
    visible: PopupPortalBounds?, backing: PopupPortalBounds?, size: PopupPortalBounds?,
    insets: PopupPortalInsets, scale: Double,
): PopupPortalBounds {
    val width = visible?.width ?: backing?.let { it.width - insets.horizontal } ?: size?.width ?: 0.0
    val height = visible?.height ?: backing?.let { it.height - insets.vertical } ?: size?.height ?: 0.0
    return PopupPortalBounds(0.0, 0.0, width * scale, height * scale)
}

internal fun fluidPopupContentGrows(from: PopupPortalBounds, to: PopupPortalBounds) =
    fluidThroughGrows(from.width, from.height, to.width, to.height)

internal enum class PopupThroughItem { From, Pivot, To }

internal fun popupThroughSlots(from: PopupPortalBounds, to: PopupPortalBounds, fromPhase: Boolean) =
    popupThroughSlots(fluidPopupContentGrows(from, to), fromPhase)

private fun popupThroughSlots(growing: Boolean, fromPhase: Boolean): Pair<PopupThroughItem, PopupThroughItem> {
    val endpoint = if (fromPhase) PopupThroughItem.From else PopupThroughItem.To
    return if (growing == fromPhase) PopupThroughItem.Pivot to endpoint else endpoint to PopupThroughItem.Pivot
}

internal data class FluidOutlineOverride(
    val bounds: PopupPortalBounds?, val cornerRadius: Double, val identityTransform: Boolean = false,
)

internal data class FluidOutlineStep(val endpoint: FluidOutlineOverride?, val companion: FluidOutlineOverride?)

internal data class FluidGrowingOutlineTargets(
    val collapsedSource: FluidOutlineStep, val overrounded: FluidOutlineStep, val fullyExpanded: FluidOutlineStep,
)

internal fun fluidOutlineRequests(
    action: FluidTimelineAction, growing: Boolean, growth: FluidGrowingOutlineTargets?, returnOutline: FluidOutlineStep?,
): List<Pair<PopupThroughItem, FluidOutlineOverride?>>? {
    val source = when (action) {
        FluidTimelineAction.Source, FluidTimelineAction.CollapsedSource -> true
        FluidTimelineAction.ExpandedOverrounded, FluidTimelineAction.FullyExpanded -> false
        else -> return null
    }
    val outline = if (!growing) returnOutline else when (action) {
        FluidTimelineAction.CollapsedSource -> growth?.collapsedSource
        FluidTimelineAction.ExpandedOverrounded -> growth?.overrounded
        FluidTimelineAction.FullyExpanded -> growth?.fullyExpanded
        else -> null
    }
    val (first, second) = popupThroughSlots(growing, fromPhase = source == growing)
    fun request(role: PopupThroughItem) = role to if (role == PopupThroughItem.Pivot) outline?.companion else outline?.endpoint
    return listOf(request(first), request(second))
}

/** The two source rectangles are in the smaller root. The two conversions resolve each target's container. */
internal fun fluidGrowingOutlineTargets(
    larger: PopupPortalBounds, pivot: PopupPortalBounds, diameter: Double,
    toSmallerContainer: (PopupPortalBounds) -> PopupPortalBounds, toPivotContainer: (PopupPortalBounds) -> PopupPortalBounds,
    companionInset: (PopupPortalBounds, Double) -> PopupPortalBounds,
): FluidGrowingOutlineTargets {
    fun roundedOrigin(value: Double): Double {
        val magnitude = kotlin.math.abs(value)
        val integral = kotlin.math.floor(magnitude)
        return Math.copySign(if (magnitude - integral >= 0.5) integral + 1.0 else integral, value)
    }
    fun squareAt(x: Double, y: Double): PopupPortalBounds {
        val square = PopupPortalBounds(0.0, 0.0, diameter, diameter).centeredAt(x, y)
        return square.copy(x = roundedOrigin(square.x), y = roundedOrigin(square.y))
    }
    fun circle(bounds: PopupPortalBounds, identity: Boolean = false): FluidOutlineOverride {
        val short = if (bounds.height < bounds.width) bounds.height else bounds.width
        return FluidOutlineOverride(bounds, short * 0.5, identity)
    }
    val collapsed = toSmallerContainer(squareAt(pivot.midX, pivot.midY))
    val region = companionInset(larger, (0.25 * diameter) * 0.5).standardized()
    val companionX = (pivot.midX * 0.75 + larger.midX * 0.25).coerceIn(region.x, region.x + region.width)
    val companionY = (pivot.midY * 0.75 + larger.midY * 0.25).coerceIn(region.y, region.y + region.height)
    val companion = circle(toPivotContainer(squareAt(companionX, companionY)))
    return FluidGrowingOutlineTargets(
        FluidOutlineStep(circle(collapsed, identity = true), null),
        FluidOutlineStep(FluidOutlineOverride(null, kotlin.math.abs(larger.width)), companion),
        FluidOutlineStep(null, companion),
    )
}

/** Both prepared rectangles use the smaller endpoint's root coordinates. */
internal fun PopupPortalBounds.growingKickTarget(pivot: PopupPortalBounds, anchorY: Double, genieScale: Double?, amount: Double = -80.0): Double {
    val larger = standardized()
    val center = genieScale ?: if (anchorY != 0.5) anchorY else {
        val fraction = (pivot.midY - larger.y) / larger.height
        if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.0, 1.0)
    }
    val fraction = (larger.height + (-60.0)) / 140.0
    val scale = if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.5, 1.0)
    return (center - (1.0 - center)) * (amount * scale)
}

internal data class FluidPopupPreview(
    override val opening: Boolean, val pivot: Rect, val root: Offset, val homeRadius: Float, val popupRadius: Float,
    val anchorY: Double, val kickScale: Double,
) : PopupOutlinePreview<Rect> {
    override fun prepare(home: Rect, envelope: Rect, density: Float): PopupOutline<Rect> = PreparedFluidOutline(home, envelope, density, this)
}

private class PreparedFluidOutline(start: Rect, end: Rect, density: Float, private val preview: FluidPopupPreview) : PopupOutline<Rect> {
    private val opening = preview.opening
    private val scale = density.toDouble()
    private fun points(r: Rect) = PopupPortalBounds((r.left - preview.root.x) / scale,
        (r.top - preview.root.y) / scale, r.width / scale, r.height / scale)
    private val home = points(start)
    private val popup = points(end)
    private val pivot = points(preview.pivot)
    override val kickScale get()=preview.kickScale
    override val growing = if (opening) fluidPopupContentGrows(home, popup) else fluidPopupContentGrows(popup, home)
    override val apertureSlot get()=if (growing) 0 else 1
    private val smaller = if (growing == opening) home else popup
    private val larger = if (growing == opening) popup else home
    private val targets = fluidGrowingOutlineTargets(larger, pivot, minOf(smaller.width, smaller.height), { it }, { it }) { r, inset ->
        requireNotNull(PopupPortalInsets(inset, inset, inset, inset).signedCrop(r))
    }

    override fun outline(action: FluidTimelineAction): List<Pair<Rect, Float>>? = fluidOutlineRequests(action, growing, targets,
        targets.fullyExpanded.takeIf { action == FullyExpanded })?.map { (role, override) ->
        val isHome = if (role == PopupThroughItem.Pivot) true else (role == PopupThroughItem.From) == opening
        val bounds = override?.bounds ?: if (role == PopupThroughItem.Pivot) pivot else if (isHome) home else popup
        val rect = Rect((bounds.x * scale + preview.root.x).toFloat(), (bounds.y * scale + preview.root.y).toFloat(),
            ((bounds.x + bounds.width) * scale + preview.root.x).toFloat(), ((bounds.y + bounds.height) * scale + preview.root.y).toFloat())
        rect to (override?.let { (it.cornerRadius * scale).toFloat() } ?: if (isHome) preview.homeRadius else preview.popupRadius)
    }

    override fun kick(action: FluidTimelineAction): Pair<Int, Float>? {
        val (slot, amount) = when (action) {
            Kick, KickEjectee -> FluidKickSpec.growingSlot to larger.growingKickTarget(pivot, preview.anchorY, null)
            KickSource -> 1 to larger.growingKickTarget(pivot, preview.anchorY, null, amount = 40.0)
            Unkick, UnkickEjectee -> FluidKickSpec.growingSlot to FluidKickSpec.unkickTarget
            UnkickSource -> 1 to FluidKickSpec.unkickTarget
            else -> return null
        }
        return slot to (amount * scale).toFloat()
    }
}
