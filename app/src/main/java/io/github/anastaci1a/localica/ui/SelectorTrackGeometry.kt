package io.github.anastaci1a.localica.ui

// Shared physical limit; also fits the existing 4 dp choice-track clearance.
private const val SelectorMaximumExtensionDp = 4f
internal val SelectorMaximumExtension = androidx.compose.ui.unit.Dp(SelectorMaximumExtensionDp)

/** Layout-space track expansion; gesture distances stay in the original host's coordinates. */
internal data class SelectorTrackGeometry(
    val travel: Float,
    val cross: Float,
    val stretch: Float,
    val wall: Float,
    val crossExtentOverride: Float? = null,
    val maximumExtension: Float = SelectorMaximumExtensionDp,
    val heldCrossInset: Float? = null,
    val heldContact: Float = 1f,
    val travelExtentOverride: Float? = null,
    val travelStartOverride: Float? = null,
) {
    init {
        require(travel.isFinite() && travel > 0f && cross.isFinite() && cross > 0f)
        require(stretch.isFinite() && wall.isFinite())
        require(heldContact.isFinite() && heldContact in 0f..1f)
        require(maximumExtension.isFinite() && maximumExtension >= 0f)
        require(heldCrossInset == null || heldCrossInset.isFinite() && heldCrossInset >= 0f && 2f * heldCrossInset < cross)
    }
    val expansion = stretch.coerceIn(0f, .04f)
    val pullFraction get() = expansion / .04f
    val travelExtent = travelExtentOverride ?: (travel + maximumExtension * pullFraction)
    // A held lens keeps its resting cross dimension. Close its physical inset by
    // bringing the shell inward, rather than inflating the lens at the endpoint.
    val crossExtent = crossExtentOverride ?: heldCrossInset?.let { cross - 2f * it * pullFraction * heldContact }
        ?: (cross / shellTravelScale)
    val travelStart = travelStartOverride ?: ((travelExtent - travel) * (wall.coerceIn(-1f, 1f) - 1f) / 2f)
    val crossStart = (cross - crossExtent) / 2f
    val travelEnd get() = travelStart + travelExtent

    // Keep the shell's recording size unchanged; only its existing graphics layer
    // consumes these values. Foreground slots consume the matching layout frame.
    val shellTravelScale get() = travelExtent / travel
    val shellCrossScale get() = crossExtent / cross
    val shellTranslation get() = travelStart + (travelExtent - travel) / 2f

    fun slotExtent(count: Int, outerInset: Float): Float {
        require(count > 0 && outerInset >= 0f && outerInset * 2f < travelExtent)
        return (travelExtent - 2f * outerInset) / count
    }

    fun slotStart(index: Int, count: Int, outerInset: Float): Float =
        travelStart + outerInset + index * slotExtent(count, outerInset)

    fun slotCenter(index: Int, count: Int, outerInset: Float): Float =
        slotStart(index, count, outerInset) + slotExtent(count, outerInset) / 2f

    fun positionAt(coordinate: Float, count: Int, outerInset: Float): Float =
        (coordinate - travelStart - outerInset) / slotExtent(count, outerInset) - .5f
}

/** Insets stay physical: navigation uses per-slot travel inset, choices outer-track inset. */
internal data class SelectorTrackInsets(val outerTravel: Float, val slotTravel: Float, val cross: Float) {
    init { require(listOf(outerTravel, slotTravel, cross).all { it.isFinite() && it >= 0f }) }
}

/** Host-relative physical coordinates; subtract the visual frame origin only when nested inside it. */
internal data class SelectorTrackSlot(
    val cell: androidx.compose.ui.geometry.Rect,
    val lens: androidx.compose.ui.geometry.Rect,
) {
    val glyphCenter get() = cell.center
}

/** Circular contact corners are constructed in final layout space, never stretched after drawing. */
internal data class SelectorTrackLens(val bounds: androidx.compose.ui.geometry.Rect, val corner: Float,
    val wall: Float, val gap: Float, val outerCorner: Float, val crossExtent: Float,
    val travelExtent: Float? = null) {
    fun contactCenters(vertical: Boolean): Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset> {
        val b = bounds
        return if (vertical) {
            val y = if (wall < 0f) b.top + corner else b.bottom - corner
            androidx.compose.ui.geometry.Offset(b.left + corner, y) to androidx.compose.ui.geometry.Offset(b.right - corner, y)
        } else {
            val x = if (wall < 0f) b.left + corner else b.right - corner
            androidx.compose.ui.geometry.Offset(x, b.top + corner) to androidx.compose.ui.geometry.Offset(x, b.bottom - corner)
        }
    }
}

/** The selected end approaches the current outer wall; its free end absorbs axial compression. */
internal fun SelectorTrackGeometry.lens(position: Float, count: Int, insets: SelectorTrackInsets,
    vertical: Boolean, rtl: Boolean, shape: GlassShape, outerCorner: Float,
    presentation: SelectorReturnFrame? = null): SelectorTrackLens {
    val logical = position.coerceIn(0f, count - 1f)
    val physical = if (!vertical && rtl) count - 1f - logical else logical
    val affinity = if (count == 1) wall else
        selectorTrackPhysicalWall(glassWallAffinity(logical, count - 1f), vertical, rtl)
    val contact = kotlin.math.abs(affinity)
    val baseGap = insets.outerTravel + insets.slotTravel
    val gap = presentation?.gap ?: (baseGap * (1f - pullFraction * contact))
    val crossGap = insets.cross * (1f - pullFraction * contact)
    val radius = (presentation?.outerCorner ?: outerCorner).coerceIn(0f, minOf(crossExtent, travelExtent) / 2f)
    val corner = presentation?.corner ?: (radius - crossGap).coerceAtLeast(0f)
    val baseMain = slotExtent(count, insets.outerTravel) - 2f * insets.slotTravel
    val baseCross = crossExtent - 2f * insets.cross
    val mainScale = if (vertical) shape.scaleY(true) else shape.scaleX()
    val crossScale = if (vertical) shape.scaleX(true) else shape.scaleY()
    val allowance = insets.cross * 1.5f
    val main = presentation?.lensTravel ?: (baseMain * mainScale).coerceIn(maxOf(1f, 2f * corner), maxOf(baseMain + allowance, 2f * corner))
        .coerceAtMost((travelExtent - gap).coerceAtLeast(1f))
    val freeCross = (baseCross * crossScale).coerceIn(maxOf(1f, baseCross - allowance), baseCross + allowance)
    val across = presentation?.lensCross ?: heldCrossInset?.let { cross - 2f * it }
        ?: if (shape == GlassShape() && pullFraction == 0f) baseCross
            else (freeCross * (1f - contact) + (crossExtent - 2f * crossGap) * contact)
    val baselineStart = travelStart + insets.outerTravel + physical * slotExtent(count, insets.outerTravel) + insets.slotTravel
    val start = baselineStart + (baseMain - main) * (affinity + 1f) / 2f + affinity * (baseGap - gap)
    val crossStart = this.crossStart + (crossExtent - across) / 2f
    val bounds = if (vertical) androidx.compose.ui.geometry.Rect(crossStart, start, crossStart + across, start + main)
        else androidx.compose.ui.geometry.Rect(start, crossStart, start + main, crossStart + across)
    return SelectorTrackLens(bounds, corner.coerceAtMost(minOf(main, across) / 2f), affinity, gap, radius, across, main)
}

internal fun SelectorTrackGeometry.slot(index: Int, count: Int, insets: SelectorTrackInsets,
    vertical: Boolean, rtl: Boolean = false): SelectorTrackSlot {
    require(index in 0 until count)
    val physicalIndex = if (!vertical && rtl) count - 1 - index else index
    val extent = slotExtent(count, insets.outerTravel)
    require(insets.slotTravel * 2f < extent && insets.cross * 2f < crossExtent)
    val start = slotStart(physicalIndex, count, insets.outerTravel)
    fun rect(alongStart: Float, acrossStart: Float, alongEnd: Float, acrossEnd: Float) =
        if (vertical) androidx.compose.ui.geometry.Rect(acrossStart, alongStart, acrossEnd, alongEnd)
        else androidx.compose.ui.geometry.Rect(alongStart, acrossStart, alongEnd, acrossEnd)
    return SelectorTrackSlot(
        cell = rect(start, crossStart, start + extent, crossStart + crossExtent),
        lens = rect(start + insets.slotTravel, crossStart + insets.cross,
            start + extent - insets.slotTravel, crossStart + crossExtent - insets.cross),
    )
}

internal fun SelectorTrackGeometry.logicalPositionAt(point: androidx.compose.ui.geometry.Offset,
    count: Int, outerInset: Float, vertical: Boolean, rtl: Boolean = false): Float {
    val physical = positionAt(if (vertical) point.y else point.x, count, outerInset)
    return if (!vertical && rtl) count - 1f - physical else physical
}

/** Resolve direction once; a caller retains the last nonzero wall through settling. */
internal fun selectorTrackPhysicalWall(logicalWall: Float, vertical: Boolean, rtl: Boolean): Float =
    logicalWall.coerceIn(-1f, 1f) * if (!vertical && rtl) -1f else 1f

/** Reduced motion and overflow have exactly the undeformed base frame. */
internal fun selectorTrackGeometry(travel: Float, cross: Float, stretch: Float, logicalWall: Float,
    vertical: Boolean, rtl: Boolean, reducedMotion: Boolean, overflow: Boolean,
    maximumExtension: Float = SelectorMaximumExtensionDp, heldCrossInset: Float? = null, heldContact: Float = 1f) = SelectorTrackGeometry(
        travel, cross, if (reducedMotion || overflow) 0f else stretch,
        selectorTrackPhysicalWall(logicalWall, vertical, rtl), maximumExtension = maximumExtension,
        heldCrossInset = heldCrossInset.takeUnless { reducedMotion || overflow },
        heldContact = heldContact)

/** Apply all released shell dimensions together, bypassing raw momentum footprint reconstruction. */
internal fun SelectorTrackGeometry.withReturnFrame(frame: SelectorReturnFrame) =
    copy(stretch = 0f, heldCrossInset = null, crossExtentOverride = frame.shellCross,
        travelExtentOverride = frame.shellTravel, travelStartOverride = frame.shellStart)

/** Record authoritative extents rather than subtracting rounded translated rectangle edges. */
internal fun SelectorTrackLens.returnFrame(geometry: SelectorTrackGeometry, vertical: Boolean) =
    SelectorReturnFrame(geometry.travelExtent, geometry.crossExtent,
        (travelExtent ?: if (vertical) bounds.height else bounds.width), crossExtent, gap, corner, outerCorner,
        if (geometry.travelStart == 0f) 0f else geometry.travelStart)

/** Rest identity depends only on layout, never the moving lens's fractional wall affinity. */
internal fun SelectorTrackGeometry.restFrame(count: Int, insets: SelectorTrackInsets,
    vertical: Boolean, outerCorner: Float): SelectorReturnFrame {
    val base = copy(stretch = 0f, heldCrossInset = null, crossExtentOverride = null,
        travelExtentOverride = null, travelStartOverride = null)
    return base.lens(0f, count, insets, vertical, rtl = false, GlassShape(), outerCorner)
        .returnFrame(base, vertical).copy(shellStart = 0f)
}

/** Project the cross-axis view for geometry checks. Production returns use the full frame. */
internal fun SelectorTrackLens.withCrossFrame(frame: SelectorCrossFrame, hostCross: Float,
    vertical: Boolean): SelectorTrackLens {
    val extent = frame.lens
    val start = (hostCross - extent) / 2f
    val adjusted = if (vertical) androidx.compose.ui.geometry.Rect(start, bounds.top, start + extent, bounds.bottom)
        else androidx.compose.ui.geometry.Rect(bounds.left, start, bounds.right, start + extent)
    return copy(bounds = adjusted, crossExtent = extent, outerCorner = frame.outerCorner,
        corner = frame.corner.coerceIn(0f, minOf(adjusted.width, adjusted.height) / 2f))
}

// Retain the calculated extent: subtracting translated Rect edges can add one Float ULP.
internal fun SelectorTrackLens.crossFrame(geometry: SelectorTrackGeometry) =
    SelectorCrossFrame(geometry.crossExtent, crossExtent, corner, outerCorner)

/** Foundation's RTL strip starts at its right edge; pointer coordinates stay physical. */
internal fun selectorContentScrollOffset(value: Int, maximum: Int, rtl: Boolean): Float =
    (if (rtl) maximum - value else value).toFloat()

/** Hit the visible rounded moving lens, rather than its selected option's entire cell. */
internal fun selectorVisibleLensContains(point: androidx.compose.ui.geometry.Offset,
    lens: SelectorTrackLens, contentOffset: Float, viewport: androidx.compose.ui.geometry.Rect): Boolean {
    if (!viewport.contains(point)) return false
    val content = point.copy(x = point.x + contentOffset)
    return lens.contour().contains(content)
}

/** Fixed capture bounds include the signed return's per-side cross clearance. */
internal fun selectorTrackRecordingBounds(travel: Float, cross: Float, clearance: Float) =
    androidx.compose.ui.geometry.Rect(-clearance, -clearance, travel + clearance, cross + clearance)
