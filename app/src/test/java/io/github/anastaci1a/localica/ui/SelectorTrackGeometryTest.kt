package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorTrackGeometryTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .0001f)

    @Test fun navigationKeepsOppositeWallAndMovesSlotCentersWithItsShell() {
        val g = SelectorTrackGeometry(360f, 64f, .04f, 1f)
        near(0f, g.travelStart)
        near(374.4f, g.travelEnd)
        near(64f / 1.04f, g.crossExtent)
        listOf(46.8f, 140.4f, 234f, 327.6f).forEachIndexed { i, center ->
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
