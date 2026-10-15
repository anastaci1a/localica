package io.github.anastaci1a.localica.ui

/** Layout-space track expansion; gesture distances stay in the original host's coordinates. */
internal data class SelectorTrackGeometry(
    val travel: Float,
    val cross: Float,
    val stretch: Float,
    val wall: Float,
) {
    init {
        require(travel.isFinite() && travel > 0f && cross.isFinite() && cross > 0f)
        require(stretch.isFinite() && wall.isFinite())
    }
    val expansion = stretch.coerceIn(0f, .04f)
    val pullFraction get() = expansion / .04f
    val travelExtent = travel * (1f + expansion)
    val crossExtent = cross / (1f + expansion)
    val travelStart = (travelExtent - travel) * (wall.coerceIn(-1f, 1f) - 1f) / 2f
    val crossStart = (cross - crossExtent) / 2f
    val travelEnd get() = travelStart + travelExtent

    // Keep the shell's recording size unchanged; only its existing graphics layer
    // consumes these values. Foreground slots consume the matching layout frame.
    val shellTravelScale get() = 1f + expansion
    val shellCrossScale get() = 1f / shellTravelScale
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
    val wall: Float, val gap: Float, val outerCorner: Float) {
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
    vertical: Boolean, rtl: Boolean, shape: GlassShape, outerCorner: Float): SelectorTrackLens {
    val logical = position.coerceIn(0f, count - 1f)
    val physical = if (!vertical && rtl) count - 1f - logical else logical
    val affinity = if (count == 1) wall * pullFraction else
        selectorTrackPhysicalWall(glassWallAffinity(logical, count - 1f), vertical, rtl)
    val contact = kotlin.math.abs(affinity)
    val baseGap = insets.outerTravel + insets.slotTravel
    val gap = baseGap * (1f - pullFraction * contact)
    val crossGap = insets.cross * (1f - pullFraction * contact)
    val radius = outerCorner.coerceIn(0f, minOf(crossExtent, travelExtent) / 2f)
    val corner = (radius - crossGap).coerceAtLeast(0f)
    val baseMain = slotExtent(count, insets.outerTravel) - 2f * insets.slotTravel
    val baseCross = crossExtent - 2f * insets.cross
    val mainScale = if (vertical) shape.scaleY(true) else shape.scaleX()
    val crossScale = if (vertical) shape.scaleX(true) else shape.scaleY()
    val allowance = insets.cross * 1.5f
    val main = (baseMain * mainScale).coerceIn(maxOf(1f, 2f * corner), maxOf(baseMain + allowance, 2f * corner))
    val freeCross = (baseCross * crossScale).coerceIn(maxOf(1f, baseCross - allowance), baseCross + allowance)
    val across = freeCross * (1f - contact) + (crossExtent - 2f * crossGap) * contact
    val baselineStart = travelStart + insets.outerTravel + physical * slotExtent(count, insets.outerTravel) + insets.slotTravel
    val start = baselineStart + (baseMain - main) * (affinity + 1f) / 2f + affinity * (baseGap - gap)
    val crossStart = this.crossStart + (crossExtent - across) / 2f
    val bounds = if (vertical) androidx.compose.ui.geometry.Rect(crossStart, start, crossStart + across, start + main)
        else androidx.compose.ui.geometry.Rect(start, crossStart, start + main, crossStart + across)
    return SelectorTrackLens(bounds, corner.coerceAtMost(minOf(main, across) / 2f), affinity, gap, radius)
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
    vertical: Boolean, rtl: Boolean, reducedMotion: Boolean, overflow: Boolean) = SelectorTrackGeometry(
        travel, cross, if (reducedMotion || overflow) 0f else stretch,
        selectorTrackPhysicalWall(logicalWall, vertical, rtl))
