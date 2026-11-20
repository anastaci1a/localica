package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

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
    from.width * from.height < to.width * to.height

internal enum class PopupThroughItem { From, Pivot, To }

internal fun popupThroughSlots(from: PopupPortalBounds, to: PopupPortalBounds, fromPhase: Boolean): Pair<PopupThroughItem, PopupThroughItem> {
    val endpoint = if (fromPhase) PopupThroughItem.From else PopupThroughItem.To
    return if (fluidPopupContentGrows(from, to) == fromPhase) PopupThroughItem.Pivot to endpoint else endpoint to PopupThroughItem.Pivot
}

internal data class FluidOutlineOverride(
    val bounds: PopupPortalBounds?, val cornerRadius: Double, val identityTransform: Boolean = false,
)

internal data class FluidOutlineStep(val endpoint: FluidOutlineOverride?, val companion: FluidOutlineOverride?)

internal data class FluidGrowingOutlineTargets(
    val collapsedSource: FluidOutlineStep, val overrounded: FluidOutlineStep, val fullyExpanded: FluidOutlineStep,
)

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
internal fun PopupPortalBounds.growingKickTarget(pivot: PopupPortalBounds, anchorY: Double, genieScale: Double?): Double {
    val larger = standardized()
    val center = genieScale ?: if (anchorY != 0.5) anchorY else {
        val fraction = (pivot.midY - larger.y) / larger.height
        if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.0, 1.0)
    }
    val fraction = (larger.height + (-60.0)) / 140.0
    val scale = if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.5, 1.0)
    return (center - (1.0 - center)) * (-80.0 * scale)
}

internal data class PopupPortalPlacement(val bounds: PopupPortalBounds, val x: Double, val y: Double)

internal fun fluidPopupPortalPlacement(
    content: PopupPortalBounds, backing: PopupPortalBounds?, mask: PopupPortalBounds?,
): PopupPortalPlacement = if (backing != null && mask != null) {
    val path = mask.standardized()
    PopupPortalPlacement(backing, backing.midX - path.x, backing.midY - path.y)
} else PopupPortalPlacement(content, content.midX, content.midY)

internal fun fluidPopupPreviewInsets(top: Double, left: Double, bottom: Double, right: Double, reversed: Boolean) =
    if (reversed) PopupPortalInsets(top, right, bottom, left) else PopupPortalInsets(top, left, bottom, right)

internal fun fluidPopupPreviewScaleChanges(old: Double, requested: Double) =
    !(kotlin.math.abs(requested - old) <= Double.fromBits(0x3cb0000000000000L))

internal fun fluidPopupAperture(width: Double, height: Double, insets: PopupPortalInsets) =
    PopupPortalBounds(0.0, 0.0, width + insets.horizontal, height + insets.vertical)

internal fun PopupPortalBounds.destinationPosition(apertureInsets: PopupPortalInsets, destinationInsets: PopupPortalInsets) =
    midX to (apertureInsets.top - destinationInsets.top)

/** Placement from the local presentation aperture and the scale already requested for this destination. */
internal fun PopupPortalBounds.presentedDestination(
    itemWidth: Double, itemHeight: Double, scale: Pair<Double, Double>, axis: Int,
    apertureInsets: PopupPortalInsets, itemInsets: PopupPortalInsets, reversed: Boolean,
): PopupPortalPlacement {
    val (sx, sy) = scale
    val apertureWidth = kotlin.math.abs(width)
    val apertureHeight = kotlin.math.abs(height)
    if (axis == 1) {
        fun left(insets: PopupPortalInsets) = if (reversed) insets.trailing else insets.leading
        val scaledWidth = itemWidth * sx
        val left = left(apertureInsets) - left(itemInsets) * sx
        val mid = midX
        val fraction = 1.0 - (mid - left) / scaledWidth
        return PopupPortalPlacement(
            PopupPortalBounds(0.0, 0.0, itemWidth, apertureHeight / sx),
            mid + scaledWidth * (fraction - 0.5), apertureInsets.top - itemInsets.top * sx,
        )
    }
    val destinationWidth = if (axis == 2) apertureWidth / sy else itemWidth
    return PopupPortalPlacement(PopupPortalBounds(0.0, 0.0, destinationWidth, itemHeight), apertureWidth * 0.5, 0.0)
}

internal fun fluidPopupDestinationScale(
    width: Double, height: Double, croppedWidth: Double, croppedHeight: Double, axis: Int,
): Pair<Double, Double> {
    val rx = if (croppedWidth == 0.0) 1.0 else width / croppedWidth
    val ry = if (croppedHeight == 0.0) 1.0 else height / croppedHeight
    return when (axis) {
        1 -> rx to rx
        2 -> ry to ry
        else -> rx to ry
    }
}

internal fun fluidPopupAutoAxis(oldWidth: Double, oldHeight: Double, targetWidth: Double, targetHeight: Double) =
    if (kotlin.math.abs(targetHeight - oldHeight) / oldHeight < kotlin.math.abs(targetWidth - oldWidth) / oldWidth) 2 else 1

internal fun fluidPopupLayoutAxis(
    topInsets: PopupPortalInsets, preferred: Int, current: Int, itemSize: PopupPortalBounds,
    presentedBounds: () -> PopupPortalBounds?,
): Int? {
    if (preferred == current || topInsets.top != 0.0 || topInsets.leading != 0.0 || topInsets.bottom != 0.0 || topInsets.trailing != 0.0) return null
    val target = itemSize.standardized()
    val old = presentedBounds()?.standardized() ?: target
    return fluidPopupAutoAxis(old.width, old.height, target.width, target.height)
}

internal fun requestFluidContentSize(
    topInsets: PopupPortalInsets, writeInsets: (PopupPortalInsets) -> Unit,
    readBounds: () -> PopupPortalBounds?, readModelInsets: () -> PopupPortalInsets,
    insetSize: (PopupPortalBounds?, PopupPortalInsets) -> Pair<Double, Double>,
    writeWidth: (Double) -> Unit, writeHeight: (Double) -> Unit,
) {
    writeInsets(topInsets)
    val (width, height) = insetSize(readBounds(), readModelInsets())
    writeWidth(width)
    writeHeight(height)
}

/** Runs inside the presentation owner's destination update. The prepared miss is allocated only when needed. */
internal fun <Destination, Item> updateFluidPopupDestination(
    destinations: MutableList<Destination>, item: Item, matchesItemId: (Destination, Item) -> Boolean,
    prepare: () -> Destination, reparent: (Destination) -> Unit, replaceItem: (Destination, Item) -> Unit, hideNew: (Destination) -> Unit,
): Destination {
    val index = destinations.indexOfFirst { matchesItemId(it, item) }
    if (index >= 0) {
        val destination = destinations.removeAt(index)
        destinations.add(destination)
        reparent(destination)
        replaceItem(destination, item)
        return destination
    }
    val destination = prepare()
    destinations.add(destination)
    if (destinations.size >= 2) hideNew(destination)
    return destination
}

internal fun <Destination : Any> List<Destination>.fluidLayoutTop(hasOverrideBounds: Boolean): Destination? =
    if (hasOverrideBounds) null else lastOrNull()

internal fun <Destination> List<Destination>.refreshFluidDestinationSizes(
    dirty: Boolean, clearDirty: () -> Unit, refresh: (Destination) -> Unit, updateModel: () -> Unit,
) {
    if (!dirty) return
    clearDirty()
    forEach(refresh)
    updateModel()
}

internal fun <Destination> List<Destination>.requestFluidDestinationProgress(
    capturedTop: Destination, isEqual: (Destination, Destination) -> Boolean, request: (Destination, Double) -> Unit,
) {
    forEach { destination -> request(destination, if (isEqual(destination, capturedTop)) 1.0 else 0.0) }
}
