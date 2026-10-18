package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorTrackGeometryTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .0001f)

    @Test fun navigationKeepsOppositeWallAndMovesSlotCentersWithItsShell() {
        val g = SelectorTrackGeometry(360f, 64f, .04f, 1f)
        near(0f, g.travelStart)
        near(364f, g.travelEnd)
        near(64f * 360f / 364f, g.crossExtent)
        listOf(45.5f, 136.5f, 227.5f, 318.5f).forEachIndexed { i, center ->
            near(center, g.slotCenter(i, 4, 0f))
        }
        near(g.travelEnd - 4f, g.slotStart(3, 4, 0f) + g.slotExtent(4, 0f) - 4f)
    }

    @Test fun leftPressureMirrorsRightPressureAndMatchesLayerBounds() {
        for (wall in listOf(-1f, 1f)) {
            val g = SelectorTrackGeometry(360f, 64f, .04f, wall)
            val layerStart = 180f - 180f * g.shellTravelScale + g.shellTranslation
            near(g.travelStart, layerStart)
            near(g.travelEnd, layerStart + 360f * g.shellTravelScale)
            near(64f, g.crossExtent * g.shellTravelScale)
            near(if (wall < 0f) 360f else 0f, if (wall < 0f) g.travelEnd else g.travelStart)
        }
    }

    @Test fun identityPreservesDifferentNavigationAndChoiceInsetTopologies() {
        val g = SelectorTrackGeometry(360f, 64f, 0f, 1f)
        near(45f, g.slotCenter(0, 4, 0f))
        near(48f, g.slotCenter(0, 4, 4f))
        near(0f, g.travelStart)
        near(0f, g.crossStart)
        near(1f, g.shellTravelScale)
        near(1f, g.shellCrossScale)
    }

    @Test fun centersRoundTripForBothAxesEdgesAndOptionCounts() {
        for (travel in listOf(64f, 360f, 721f)) for (cross in listOf(52f, 300f))
            for (wall in listOf(-1f, 1f)) for (stretch in listOf(0f, .01f, .04f))
                for (count in listOf(1, 2, 4, 7)) for (inset in listOf(0f, 4f)) {
                    val g = SelectorTrackGeometry(travel, cross, stretch, wall)
                    repeat(count) { i -> near(i.toFloat(), g.positionAt(g.slotCenter(i, count, inset), count, inset)) }
                }
    }
}

class SelectorTrackPlacementTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .0002f)
    private val navigation = SelectorTrackInsets(0f, 4f, 4f)
    private val choices = SelectorTrackInsets(4f, 0f, 4f)

    @Test fun bothInsetTopologiesShareTravelWallsAndKeepPhysicalInsetThickness() {
        for (insets in listOf(navigation, choices)) for (vertical in listOf(false, true))
            for (rtl in listOf(false, true)) for (logicalWall in listOf(-1f, 1f))
                for (count in listOf(1, 2, 4, 7)) {
                    val geometry = selectorTrackGeometry(360f, 64f, .04f, logicalWall,
                        vertical, rtl, reducedMotion = false, overflow = false)
                    val physicalWall = selectorTrackPhysicalWall(logicalWall, vertical, rtl)
                    val logicalIndex = if (logicalWall < 0) 0 else count - 1
                    val slot = geometry.slot(logicalIndex, count, insets, vertical, rtl)
                    val lensEdge = if (vertical) { if (physicalWall < 0) slot.lens.top else slot.lens.bottom }
                        else if (physicalWall < 0) slot.lens.left else slot.lens.right
                    val inset = insets.outerTravel + insets.slotTravel
                    near(if (physicalWall < 0) geometry.travelStart + inset else geometry.travelEnd - inset, lensEdge)
                    val across = if (vertical) slot.lens.width else slot.lens.height
                    near(geometry.crossExtent - insets.cross * 2, across)
                }
    }

    @Test fun glyphCentersMapToLogicalOptionsForAxesRtlAndScaledTextExtents() {
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
            for (count in listOf(1, 2, 4, 7)) for (height in listOf(52f, 78f, 104f))
                for (insets in listOf(navigation, choices)) {
                    val geometry = SelectorTrackGeometry(if (vertical) count * height + 8f else 360f,
                        if (vertical) 300f else height + 8f, .04f, -1f)
                    repeat(count) { index ->
                        val slot = geometry.slot(index, count, insets, vertical, rtl)
                        near(index.toFloat(), geometry.logicalPositionAt(slot.glyphCenter,
                            count, insets.outerTravel, vertical, rtl))
                        near(slot.cell.center.x, slot.lens.center.x)
                        near(slot.cell.center.y, slot.lens.center.y)
                    }
                }
    }

    @Test fun reducedMotionAndOverflowRetainExactIdentityFrame() {
        for (reduced in listOf(false, true)) for (overflow in listOf(false, true)) {
            if (!reduced && !overflow) continue
            val geometry = selectorTrackGeometry(360f, 64f, .04f, -1f, false, true, reduced, overflow)
            val identity = SelectorTrackGeometry(360f, 64f, 0f, 1f)
            assertEquals(identity, geometry)
            repeat(4) { index -> assertEquals(identity.slot(index, 4, navigation, false, true),
                geometry.slot(index, 4, navigation, false, true)) }
        }
    }
}

class SelectorTrackContactTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .001f)

    @Test fun pullProgressClosesTheGapAtEitherPhysicalWallForBothAxesAndRtl() {
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
            for (logicalWall in listOf(-1f, 1f)) for (navigation in listOf(false, true)) {
                val insets = if (navigation) SelectorTrackInsets(0f, 4f, 4f) else SelectorTrackInsets(4f, 0f, 4f)
                for (pull in listOf(0f, .25f, .5f, .75f, 1f)) {
                    val g = selectorTrackGeometry(360f, 64f, pull * .04f, logicalWall, vertical, rtl, false, false)
                    val lens = g.lens(if (logicalWall < 0) 0f else 3f, 4, insets, vertical, rtl,
                        GlassShape(strain = -.16f, depth = -.12f), 24f)
                    val edge = if (vertical) { if (lens.wall < 0) lens.bounds.top else lens.bounds.bottom }
                        else if (lens.wall < 0) lens.bounds.left else lens.bounds.right
                    near(4f * (1f - pull), lens.gap)
                    near(if (lens.wall < 0) g.travelStart + lens.gap else g.travelEnd - lens.gap, edge)
                }
            }
    }

    @Test fun contactCornerCentersRemainConcentricAtFractionalDimensionsAndDensities() {
        for (density in listOf(1f, 2.625f, 3f)) for (vertical in listOf(false, true))
            for (wall in listOf(-1f, 1f)) for (pull in listOf(0f, .3f, .8f, 1f)) {
                val g = SelectorTrackGeometry(360.25f * density, 64.5f * density, pull * .04f, wall)
                val lens = g.lens(if (wall < 0) 0f else 3f, 4,
                    SelectorTrackInsets(4f * density, 0f, 4f * density), vertical, false,
                    GlassShape(-.16f, -.1f), 24f * density)
                val along = if (wall < 0) g.travelStart + lens.outerCorner else g.travelEnd - lens.outerCorner
                val (first, second) = lens.contactCenters(vertical)
                near(along, if (vertical) first.y else first.x)
                near(along, if (vertical) second.y else second.x)
                near(g.crossStart + lens.outerCorner, if (vertical) first.x else first.y)
                near(g.crossStart + g.crossExtent - lens.outerCorner, if (vertical) second.x else second.y)
            }
    }

    @Test fun releasingStrainOscillatesAlongTheTravelAxis() {
        val g = SelectorTrackGeometry(360f, 64f, 0f, 1f)
        val insets = SelectorTrackInsets(4f, 0f, 4f)
        for (vertical in listOf(false, true)) {
            val compressed = g.lens(1.5f, 4, insets, vertical, false, GlassShape(-.1f), 24f).bounds
            val extended = g.lens(1.5f, 4, insets, vertical, false, GlassShape(.1f), 24f).bounds
            org.junit.Assert.assertTrue(if (vertical) extended.height > compressed.height else extended.width > compressed.width)
            org.junit.Assert.assertTrue(if (vertical) extended.width < compressed.width else extended.height < compressed.height)
        }
    }
}

class SelectorViewportPickupTest {
    @Test fun rtlOffsetAndVisibleRoundedLensAreMappedExactlyOnce() {
        for (rtl in listOf(false, true)) {
            val offset = selectorContentScrollOffset(50, 300, rtl)
            assertEquals(if (rtl) 250f else 50f, offset, 0f)
            val bounds = androidx.compose.ui.geometry.Rect(offset + 20f, 4f, offset + 100f, 60f)
            val lens = SelectorTrackLens(bounds, 20f, 0f, 4f, 24f, 64f)
            val viewport = androidx.compose.ui.geometry.Rect(0f, 0f, 80f, 64f)
            org.junit.Assert.assertTrue(selectorVisibleLensContains(androidx.compose.ui.geometry.Offset(50f, 32f), lens, offset, viewport))
            org.junit.Assert.assertFalse(selectorVisibleLensContains(androidx.compose.ui.geometry.Offset(21f, 5f), lens, offset, viewport))
            org.junit.Assert.assertFalse(selectorVisibleLensContains(androidx.compose.ui.geometry.Offset(90f, 32f), lens, offset, viewport))
            org.junit.Assert.assertFalse(selectorVisibleLensContains(androidx.compose.ui.geometry.Offset(10f, 32f), lens, offset, viewport))
        }
    }
}

/** Physical-distance acceptance, independent of the legacy percentage fixture above. */
class SelectorBoundedHeldGeometryTest {
    private fun near(expected: Float, actual: Float, density: Float = 1f) =
        assertEquals(expected, actual, .001f * density)

    @Test fun extensionHasOnePhysicalLimitAcrossLengthsDensitiesAxesAndDirections() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f))
            for (length in listOf(64f, 280f, 720f, 1200f))
                for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
                    for (wall in listOf(-1f, 1f)) for (pull in listOf(0f, .25f, .7f, 1f)) {
                        val g = selectorTrackGeometry(length * density, 64f * density, .04f * pull,
                            wall, vertical, rtl, false, false, maximumExtension = 4f * density)
                        near(4f * pull, (g.travelExtent - g.travel) / density)
                        val physical = selectorTrackPhysicalWall(wall, vertical, rtl)
                        near(if (physical < 0f) g.travel else 0f,
                            if (physical < 0f) g.travelEnd else g.travelStart, density)
                        repeat(4) { index ->
                            val slot = g.slot(index, 4, SelectorTrackInsets(0f, 0f, 4f * density), vertical, rtl)
                            near(index.toFloat(), g.logicalPositionAt(slot.glyphCenter, 4, 0f, vertical, rtl))
                        }
                    }
    }

    @Test fun heldCrossDimensionIsFixedWhileContactCornersRemainConcentric() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f))
            for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
                for (count in listOf(1, 3, 12)) for (wall in listOf(-1f, 1f))
                    for (pull in listOf(0f, .25f, .7f, 1f)) {
                        val cross = (if (vertical) 300f else 64f) * density
                        val travel = (if (vertical) count * 52f + 8f else 280f) * density
                        val inset = 4f * density
                        for (position in listOf(0f, .125f, (count - 1f) / 2f, count - 1f)) {
                            val bounded = position.coerceIn(0f, count - 1f)
                            val contact = if (count == 1) 1f else kotlin.math.abs(glassWallAffinity(bounded, count - 1f))
                            val g = selectorTrackGeometry(travel, cross, .04f * pull, wall,
                                vertical, rtl, false, false, maximumExtension = 4f * density,
                                heldCrossInset = inset, heldContact = contact)
                            val radius = 24f * density - inset * pull * contact
                            val lens = g.lens(bounded, count, SelectorTrackInsets(inset, 0f, inset),
                                vertical, rtl, GlassShape(-.16f, -.2f), radius)
                            near(cross - 2f * inset, lens.crossExtent, density)
                            near(cross / 2f, if (vertical) lens.bounds.center.x else lens.bounds.center.y, density)
                            org.junit.Assert.assertTrue(lens.crossExtent <= g.crossExtent)
                            if (kotlin.math.abs(lens.wall) == 1f) {
                                val expectedAlong = if (lens.wall < 0f) g.travelStart + radius else g.travelEnd - radius
                                val centers = lens.contactCenters(vertical)
                                for (center in listOf(centers.first, centers.second))
                                    near(expectedAlong, if (vertical) center.y else center.x, density)
                                near(g.crossStart + radius, if (vertical) centers.first.x else centers.first.y, density)
                                near(g.crossStart + g.crossExtent - radius,
                                    if (vertical) centers.second.x else centers.second.y, density)
                                near(inset * (1f - pull), lens.gap, density)
                            }
                        }
                    }
    }

    @Test fun heldNavigationUsesTheSameFixedCrossAndConcentricContactWithSlotInsets() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f)) for (rtl in listOf(false, true))
            for (wall in listOf(-1f, 1f)) for (pull in listOf(0f, .25f, .7f, 1f)) {
                val inset = 4f * density
                val g = selectorTrackGeometry(280f * density, 64f * density, .04f * pull,
                    wall, false, rtl, false, false, maximumExtension = 4f * density,
                    heldCrossInset = inset)
                val lens = g.lens(if (wall < 0f) 0f else 2f, 3, SelectorTrackInsets(0f, inset, inset),
                    false, rtl, GlassShape(-.16f, -.2f), g.crossExtent / 2f)
                near(56f * density, lens.crossExtent, density)
                val (top, bottom) = lens.contactCenters(false)
                val along = if (lens.wall < 0f) g.travelStart + lens.outerCorner else g.travelEnd - lens.outerCorner
                near(along, top.x, density); near(along, bottom.x, density)
                near(g.crossStart + lens.outerCorner, top.y, density)
                near(g.crossStart + g.crossExtent - lens.outerCorner, bottom.y, density)
                near(inset * (1f - pull), lens.gap, density)
            }
    }

    @Test fun suppressedGeometryDoesNotApplyHeldCrossContraction() {
        for (reduced in listOf(false, true)) for (overflow in listOf(false, true)) {
            if (!reduced && !overflow) continue
            val g = selectorTrackGeometry(900f, 300f, .04f, 1f, true, false,
                reduced, overflow, maximumExtension = 12f, heldCrossInset = 12f)
            near(900f, g.travelExtent)
            near(300f, g.crossExtent)
            org.junit.Assert.assertNull(g.heldCrossInset)
        }
    }
}

class SelectorCoupledPlacementTest {
    private fun near(expected: Float, actual: Float, density: Float = 1f) =
        assertEquals(expected, actual, .001f * density)

    @Test fun capturedFullFrameRoundTripsWithoutReadingIndependentBodyStrain() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f)) for (vertical in listOf(false, true))
            for (rtl in listOf(false, true)) for (wall in listOf(-1f, 1f)) {
                val inset = 4f * density
                val geometry = selectorTrackGeometry(360f * density, 64f * density, .04f, wall,
                    vertical, rtl, false, false, maximumExtension = inset, heldCrossInset = inset)
                val insets = SelectorTrackInsets(inset, 0f, inset)
                val position = if (wall < 0f) 0f else 3f
                val raw = geometry.lens(position, 4, insets, vertical, rtl, GlassShape(-.16f, -.2f), 20f * density)
                val captured = raw.returnFrame(geometry, vertical)
                val applied = geometry.withReturnFrame(captured)
                for (body in listOf(GlassShape(), GlassShape(.2f, .2f), GlassShape(-.2f, -.2f))) {
                    val lens = applied.lens(position, 4, insets, vertical, rtl, body, 100f, presentation = captured)
                    near(raw.bounds.left, lens.bounds.left, density); near(raw.bounds.top, lens.bounds.top, density)
                    near(raw.bounds.right, lens.bounds.right, density); near(raw.bounds.bottom, lens.bounds.bottom, density)
                    near(raw.gap, lens.gap, density); near(raw.corner, lens.corner, density)
                    near(geometry.travelStart, applied.travelStart, density)
                    near(geometry.travelExtent, applied.travelExtent, density)
                }
            }
    }

    @Test fun shellAndForegroundRecordingsHaveFixedCrossClearanceAndOneOriginTranslation() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f)) for (travel in listOf(180f, 1200f)) {
            val limits = SelectorReturnLimits(4f * density, 4f * density, -1f)
            val bounds = selectorTrackRecordingBounds(travel * density, 64f * density, limits.crossClearance)
            near(-4f * density, bounds.top, density); near(68f * density, bounds.bottom, density)
            val content = androidx.compose.ui.geometry.Rect(0f, 0f, travel * density, 64f * density)
                .translate(-bounds.topLeft)
            near(0f, bounds.left + content.left, density)
            near(0f, bounds.top + content.top, density)
            near(travel * density, bounds.left + content.right, density)
            near(64f * density, bounds.top + content.bottom, density)
        }
    }

    @Test fun restIdentityDoesNotDependOnLiveFractionalPositionOrSignedZeroWall() {
        val insets = SelectorTrackInsets(0f, 4f, 4f)
        val base = SelectorTrackGeometry(240f, 64f, 0f, 1f)
        val expected = base.restFrame(4, insets, false, 32f)
        for (position in listOf(0f, .1387f, .5f, 1.25f, 2.8613f, 3f)) for (wall in listOf(-1f, 1f)) {
            val moving = base.copy(wall = wall,
                heldContact = kotlin.math.abs(glassWallAffinity(position, 3f)))
            assertEquals(expected, moving.restFrame(4, insets, false, 32f))
        }
        near(56f, expected.lensCross)
    }

    @Test fun circularAndNarrowNavigationCellsRetainBothReboundPolarities() {
        val insets = SelectorTrackInsets(0f, 4f, 4f)
        for (cellWidth in listOf(48f, 56f, 60f, 64f)) for (count in listOf(1, 3, 4)) {
            val geometry = SelectorTrackGeometry(cellWidth * count, 64f, .04f, 1f,
                maximumExtension = 4f, heldCrossInset = 4f)
            val raw = geometry.lens(count - 1f, count, insets, false, false, GlassShape(-.16f, -.2f), 28f)
            val rest = geometry.restFrame(count, insets, false, 32f)
            val release = SelectorCrossReturn(raw.returnFrame(geometry, false), rest, SelectorReturnLimits(4f, 4f, 1f))
            var contracted = false; var expandedAfterContraction = false
            repeat(360) {
                val frame = release.advance(1f / 120f, 8f)
                contracted = contracted || frame.lensTravel < rest.lensTravel - .01f && frame.lensCross > rest.lensCross + .01f
                expandedAfterContraction = expandedAfterContraction || contracted &&
                    frame.lensTravel > rest.lensTravel + .01f && frame.lensCross < rest.lensCross - .01f
                org.junit.Assert.assertTrue(selectorReturnRoundedContains(frame))
            }
            org.junit.Assert.assertTrue("$count navigation cells at $cellWidth dp must compress travel while expanding cross", contracted)
            org.junit.Assert.assertTrue("$count navigation cells at $cellWidth dp must reverse that phase", expandedAfterContraction)
        }
    }

    @Test fun releasedContactRemainsRoundedContainedAcrossAxesRtlAndNarrowHosts() {
        for (density in listOf(1f, 2.625f)) for (vertical in listOf(false, true))
            for (rtl in listOf(false, true)) for (count in listOf(1, 3, 7))
                for (wall in listOf(-1f, 1f)) for (damping in listOf(2f, 8f, 40f)) {
                    val inset = 4f * density
                    val travel = (if (count == 1) 48f else count * 72f) * density
                    val cross = (if (vertical) 300f else 64f) * density
                    val geometry = selectorTrackGeometry(travel, cross, .04f, wall, vertical, rtl,
                        false, false, maximumExtension = inset, heldCrossInset = inset)
                    val insets = SelectorTrackInsets(inset, 0f, inset)
                    val position = if (wall < 0f) 0f else count - 1f
                    val rawRadius = (24f * density - inset).coerceAtMost(minOf(geometry.crossExtent, geometry.travelExtent) / 2f)
                    val raw = geometry.lens(position, count, insets, vertical, rtl, GlassShape(-.16f, -.2f), rawRadius)
                    val base = geometry.copy(stretch = 0f, heldCrossInset = null)
                    val baseLens = base.lens(position, count, insets, vertical, rtl, GlassShape(), 24f * density)
                    val rest = baseLens.returnFrame(base, vertical)
                    val release = SelectorCrossReturn(raw.returnFrame(geometry, vertical), rest,
                        SelectorReturnLimits(inset, inset, geometry.wall))
                    repeat(240) {
                        val frame = release.advance(1f / 120f, damping)
                        val applied = geometry.withReturnFrame(frame)
                        val lens = applied.lens(position, count, insets, vertical, rtl, GlassShape(), 0f, frame)
                        org.junit.Assert.assertTrue(frame.lensCross <= frame.shellCross)
                        org.junit.Assert.assertTrue(frame.lensTravel + frame.gap <= frame.shellTravel + .001f * density)
                        org.junit.Assert.assertTrue(selectorReturnRoundedContains(frame))
                        val shellBounds = if (vertical) androidx.compose.ui.geometry.Rect(applied.crossStart, applied.travelStart,
                            applied.crossStart + applied.crossExtent, applied.travelEnd)
                            else androidx.compose.ui.geometry.Rect(applied.travelStart, applied.crossStart,
                                applied.travelEnd, applied.crossStart + applied.crossExtent)
                        // Independent point oracle: sample every inner corner arc against
                        // the actual placed outer rounded rectangle, including its far wall.
                        val epsilon = 8f * Math.ulp(maxOf(travel, cross))
                        for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) for (step in 0..8) {
                            val angle = step * Math.PI / 16.0
                            val cx = if (sx < 0) lens.bounds.left + lens.corner else lens.bounds.right - lens.corner
                            val cy = if (sy < 0) lens.bounds.top + lens.corner else lens.bounds.bottom - lens.corner
                            val x = cx + sx * lens.corner * kotlin.math.cos(angle)
                            val y = cy + sy * lens.corner * kotlin.math.sin(angle)
                            val radius = frame.outerCorner.toDouble()
                            val ox = x.coerceIn(shellBounds.left + radius, shellBounds.right - radius)
                            val oy = y.coerceIn(shellBounds.top + radius, shellBounds.bottom - radius)
                            org.junit.Assert.assertTrue((x - ox) * (x - ox) + (y - oy) * (y - oy) <= (radius + epsilon) * (radius + epsilon))
                        }
                    }
                }
    }
}
