package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.engine.FluidPopupContentSlot
import io.github.anastaci1a.localica.ui.spec.FluidKickSpec
import io.github.anastaci1a.localica.ui.spec.FluidOutlineContentInputs
import io.github.anastaci1a.localica.ui.spec.FluidOutlineItemInputs
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import io.github.anastaci1a.localica.ui.spec.PopupOutline
import io.github.anastaci1a.localica.ui.spec.PopupOutlinePose
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
}

internal data class PopupPortalInsets(val top: Double, val leading: Double, val bottom: Double, val trailing: Double) {
    val horizontal get() = leading + trailing
    val vertical get() = top + bottom

    fun signedCrop(bounds: PopupPortalBounds?): PopupPortalBounds? = bounds?.standardized()?.let {
        PopupPortalBounds(it.x + leading, it.y + top, it.width - horizontal, it.height - vertical)
    }
}

internal data class FluidDestinationPlacement(val layer: PopupPortalBounds, val scaleX: Double, val scaleY: Double)

internal fun fluidDestinationPlacement(
    contentWidth: Double, contentHeight: Double, apertureInsets: PopupPortalInsets, item: PopupPortalBounds,
    destinationInsets: PopupPortalInsets, axis: Int, leftToRight: Boolean = true,
): FluidDestinationPlacement {
    val destination = destinationInsets.signedCrop(item)?.takeIf { it.width >= 0.0 && it.height >= 0.0 }
    val width = kotlin.math.abs(contentWidth)
    val height = kotlin.math.abs(contentHeight)
    val apertureWidth = width + apertureInsets.horizontal
    val apertureHeight = height + apertureInsets.vertical
    val rx = destination?.width?.takeUnless { it == 0.0 }?.let { width / it } ?: 1.0
    val ry = destination?.height?.takeUnless { it == 0.0 }?.let { height / it } ?: 1.0
    val middle = apertureWidth * 0.5
    return when (axis) {
        1 -> {
            val span = item.width * rx
            val left = if (leftToRight) apertureInsets.leading else apertureInsets.trailing
            val destinationLeft = if (leftToRight) destinationInsets.leading else destinationInsets.trailing
            val x = left - destinationLeft * rx
            val fraction = 1.0 - (middle - x) / span
            FluidDestinationPlacement(
                PopupPortalBounds(middle + span * (fraction - 0.5), apertureInsets.top - destinationInsets.top * rx,
                    item.width, apertureHeight / rx), rx, rx,
            )
        }
        2 -> FluidDestinationPlacement(PopupPortalBounds(middle, 0.0, apertureWidth / ry, item.height), ry, ry)
        else -> FluidDestinationPlacement(PopupPortalBounds(middle, 0.0, item.width, item.height), rx, ry)
    }
}

internal data class FluidPortalPlacement(
    val origin: Offset, val scaleX: Float, val scaleY: Float, val clip: RoundRect?, val extent: Rect,
)

private val FluidContentInsets = PopupPortalInsets(0.0, 0.0, 0.0, 0.0)

internal fun FluidPopupContentSlot.placeDestination(size: Size, root: Offset, leftToRight: Boolean): FluidPortalPlacement? {
    if (blend.layerOpacity <= 0f || aperture.width <= 0f || aperture.height <= 0f) return null
    val itemBounds = PopupPortalBounds(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
    val placement = fluidDestinationPlacement(aperture.width.toDouble(), aperture.height.toDouble(),
        FluidContentInsets, itemBounds, FluidContentInsets, item.contentScaleAxis, leftToRight)
    val (layer, x, y) = placement
    val left = aperture.left + layer.x - (layer.width * x * 0.5)
    val top = aperture.top + layer.y
    val origin = Offset((left - root.x).toFloat(), (top - root.y).toFloat())
    if (item.prefersUnclippedContents) {
        val extent = Rect(origin, Size((itemBounds.width * x).toFloat(), (itemBounds.height * y).toFloat()))
        return FluidPortalPlacement(origin, x.toFloat(), y.toFloat(), null, extent)
    }
    val clip = RoundRect(Rect(
        ((aperture.left - left) / x).toFloat(), ((aperture.top - top) / y).toFloat(),
        ((aperture.right - left) / x).toFloat(), ((aperture.bottom - top) / y).toFloat(),
    ), CornerRadius((aperture.topLeftCornerRadius.x / x).toFloat(), (aperture.topLeftCornerRadius.y / y).toFloat()))
    return FluidPortalPlacement(origin, x.toFloat(), y.toFloat(), clip, aperture.boundingRect.translate(-root))
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
    val bounds: PopupPortalBounds?, val cornerRadius: Double, val scale: Double = 1.0,
)

internal data class FluidOutlineTargets(
    val sourcePivot: FluidOutlineOverride?, val collapsedSource: FluidOutlineOverride,
    val companion: FluidOutlineOverride, val overrounded: FluidOutlineOverride,
)

internal fun fluidOutlineRequests(
    action: FluidTimelineAction, growing: Boolean, targets: FluidOutlineTargets,
): List<Pair<PopupThroughItem, FluidOutlineOverride?>>? {
    val source = when (action) {
        Source, CollapsedSource -> true
        ExpandedOverrounded, FullyExpanded -> false
        else -> return null
    }
    val (first, second) = popupThroughSlots(growing, fromPhase = source == growing)
    fun request(role: PopupThroughItem) = role to when {
        role == PopupThroughItem.Pivot -> if (source) targets.sourcePivot else targets.companion
        action == CollapsedSource -> targets.collapsedSource
        action == ExpandedOverrounded -> targets.overrounded
        else -> null
    }
    return listOf(request(first), request(second))
}

/** The two source rectangles are in the smaller root. The two conversions resolve each target's container. */
internal fun fluidOutlineTargets(
    smaller: PopupPortalBounds, larger: PopupPortalBounds, pivot: PopupPortalBounds,
    toSmallerContainer: (PopupPortalBounds) -> PopupPortalBounds, toPivotContainer: (PopupPortalBounds) -> PopupPortalBounds,
): FluidOutlineTargets {
    val diameter = minOf(smaller.width, smaller.height)
    fun roundedOrigin(value: Double): Double {
        val magnitude = kotlin.math.abs(value)
        val integral = kotlin.math.floor(magnitude)
        return Math.copySign(if (magnitude - integral >= 0.5) integral + 1.0 else integral, value)
    }
    fun squareAt(x: Double, y: Double, side: Double): PopupPortalBounds {
        val square = PopupPortalBounds(0.0, 0.0, side, side).centeredAt(x, y)
        return square.copy(x = roundedOrigin(square.x), y = roundedOrigin(square.y))
    }
    fun circle(bounds: PopupPortalBounds, scale: Double = 1.0) =
        FluidOutlineOverride(bounds, minOf(bounds.width, bounds.height) * 0.5, scale)
    fun inset(bounds: PopupPortalBounds, x: Double, y: Double) =
        requireNotNull(PopupPortalInsets(y, x, y, x).signedCrop(bounds))
    fun clamp(value: Double, lower: Double, upper: Double): Double {
        require(lower <= upper)
        return if (value <= upper) maxOf(value, lower) else upper
    }
    fun PopupPortalBounds.squareClampedAt(
        x: Double, y: Double, side: Double, convert: (PopupPortalBounds) -> PopupPortalBounds,
    ): PopupPortalBounds {
        if (width < 0 || height < 0)
            return PopupPortalBounds(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, side, side)
        return convert(squareAt(clamp(x, this.x, this.x + width), clamp(y, this.y, this.y + height), side))
    }

    val collapsed = circle(toSmallerContainer(squareAt(pivot.midX, pivot.midY, diameter)))
    val companionInset = (diameter * 0.25) * 0.5
    val region = inset(larger, companionInset, companionInset)
    val companion = circle(region.squareClampedAt(
        pivot.midX * 0.75 + larger.midX * 0.25, pivot.midY * 0.75 + larger.midY * 0.25, diameter, toPivotContainer,
    ), 0.25)
    val sourceRegion = inset(smaller, pivot.width * 0.5, pivot.height * 0.5)
    val largerSide = if (larger.width <= larger.height) larger.height else larger.width
    val sourcePivot = if (largerSide > 0) {
        val bounds = sourceRegion.squareClampedAt(pivot.midX, pivot.midY, larger.width, toPivotContainer)
        circle(bounds, pivot.height * 0.5 / largerSide)
    } else {
        null
    }
    return FluidOutlineTargets(sourcePivot, collapsed, companion, FluidOutlineOverride(null, larger.width))
}

/** Both prepared rectangles use the smaller endpoint's root coordinates. */
internal fun PopupPortalBounds.growingKickTarget(pivot: PopupPortalBounds, anchorY: Double, genieScale: Double?, amount: Double = FluidKickSpec.growingAmount): Double {
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
    val pivot: Rect, val root: Offset, val homeRadius: Float, val popupRadius: Float,
    val anchorY: Double, val hostScaleY: Double,
    val sourceIsGlass: Boolean = false,
) : PopupOutlinePreview<Rect> {
    override fun prepare(home: Rect, envelope: Rect, density: Float, opening: Boolean): PopupOutline<Rect> =
        PreparedFluidOutline(home, envelope, density, opening, this)
}

private class PreparedFluidOutline(start: Rect, end: Rect, density: Float, private val opening: Boolean, private val preview: FluidPopupPreview) : PopupOutline<Rect> {
    private val scale = density.toDouble()
    private fun points(r: Rect) = PopupPortalBounds((r.left - preview.root.x) / scale,
        (r.top - preview.root.y) / scale, r.width / scale, r.height / scale)
    private val home = points(start)
    private val popup = points(end)
    private val pivot = points(preview.pivot)
    override val hostScaleY get() = preview.hostScaleY
    override val contentInputs = FluidOutlineContentInputs(
        home = FluidOutlineItemInputs(preview.sourceIsGlass),
        popup = FluidOutlineItemInputs(isGlass = true),
    )
    override val growing = if (opening) fluidPopupContentGrows(home, popup) else fluidPopupContentGrows(popup, home)
    override val apertureSlot get()=if (growing) 0 else 1
    private val smaller = if (growing == opening) home else popup
    private val larger = if (growing == opening) popup else home
    private val targets = fluidOutlineTargets(smaller, larger, pivot, { it }, { it })

    override fun outline(action: FluidTimelineAction): List<PopupOutlinePose<Rect>>? = fluidOutlineRequests(action, growing, targets)?.map { (role, override) ->
        val isHome = if (role == PopupThroughItem.Pivot) true else (role == PopupThroughItem.From) == opening
        val bounds = override?.bounds ?: if (role == PopupThroughItem.Pivot) pivot else if (isHome) home else popup
        val preservesCenter = bounds.x == Double.POSITIVE_INFINITY && bounds.y == Double.POSITIVE_INFINITY
        val layout = if (preservesCenter) bounds.centeredAt(pivot.midX, pivot.midY) else bounds
        val rect = Rect((layout.x * scale + preview.root.x).toFloat(), (layout.y * scale + preview.root.y).toFloat(),
            ((layout.x + layout.width) * scale + preview.root.x).toFloat(), ((layout.y + layout.height) * scale + preview.root.y).toFloat())
        val radius = override?.let { (it.cornerRadius * scale).toFloat() } ?: if (isHome) preview.homeRadius else preview.popupRadius
        PopupOutlinePose(rect, radius, (override?.scale ?: 1.0).toFloat(), preservesCenter)
    }

    override fun kick(action: FluidTimelineAction): Pair<Int, Float>? {
        val (slot, amount) = when (action) {
            Kick, KickEjectee -> FluidKickSpec.growingSlot to larger.growingKickTarget(pivot, preview.anchorY, null)
            KickSource -> 1 to larger.growingKickTarget(pivot, preview.anchorY, null, FluidKickSpec.sourceAmount)
            Unkick, UnkickEjectee -> FluidKickSpec.growingSlot to FluidKickSpec.unkickTarget
            UnkickSource -> 1 to FluidKickSpec.unkickTarget
            else -> return null
        }
        return slot to (amount * scale).toFloat()
    }
}
