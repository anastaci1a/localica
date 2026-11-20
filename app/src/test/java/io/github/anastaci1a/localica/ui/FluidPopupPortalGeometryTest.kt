package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidPopupPortalGeometryTest {
    private val backing = PopupPortalBounds(10.0, 20.0, 80.0, 50.0)
    private val path = PopupPortalBounds(8.0, 16.0, 40.0, 25.0)
    private val insets = PopupPortalInsets(top = 3.0, leading = 2.0, bottom = 5.0, trailing = 6.0)

    @Test fun apertureAndBoundsClippingHaveIndependentConditions() {
        assertTrue(fluidPopupClipsBounds(9))
        assertFalse(fluidPopupClipsBounds(12))
        assertNull(fluidPopupVisibleMask(null, roundedRect = false, equalsReferenceBounds = false))
        assertNull(fluidPopupVisibleMask(path, roundedRect = true, equalsReferenceBounds = true))
        assertEquals(path, fluidPopupVisibleMask(path, roundedRect = false, equalsReferenceBounds = true))
        assertEquals(path, fluidPopupVisibleMask(path, roundedRect = true, equalsReferenceBounds = false))
        assertEquals(-8.0 to -16.0, path.maskOffset())
    }

    @Test fun portalUsesVisibleExtentThenBackingThenDeclaredSize() {
        val visible = fluidPopupContentBounds(path, backing, null, insets, 1.5)
        val cropped = fluidPopupContentBounds(null, backing, path, insets, 1.5)
        val declared = fluidPopupContentBounds(null, null, path, insets, 2.0)
        assertEquals(PopupPortalBounds(0.0, 0.0, 60.0, 37.5), visible)
        assertEquals(PopupPortalBounds(0.0, 0.0, 108.0, 63.0), cropped)
        assertEquals(PopupPortalBounds(0.0, 0.0, 80.0, 50.0), declared)
        assertEquals(PopupPortalBounds(0.0, 0.0, 0.0, 0.0), fluidPopupContentBounds(null, null, null, insets, 2.0))

        assertEquals(PopupPortalPlacement(backing, 42.0, 29.0), fluidPopupPortalPlacement(visible, backing, path))
        assertEquals(PopupPortalPlacement(visible, 30.0, 18.75), fluidPopupPortalPlacement(visible, null, path))
        assertEquals(PopupPortalPlacement(cropped, 54.0, 31.5), fluidPopupPortalPlacement(cropped, backing, null))
    }

    @Test fun cropStandardizesBeforeApplyingDirectionalInsets() {
        val original = PopupPortalBounds(20.0, 40.0, -14.0, -23.0)
        val crop = requireNotNull(insets.signedCrop(original))
        assertEquals(PopupPortalBounds(8.0, 20.0, 6.0, 15.0), crop)
        assertEquals(2.0 to 1.0, original.cropOffset(crop.midX, crop.midY))
        assertNull(insets.signedCrop(null))
        assertEquals(0.0, requireNotNull(insets.copy(trailing = 12.0).signedCrop(original)).width, 0.0)
        assertEquals(-1.0, requireNotNull(insets.copy(trailing = 13.0).signedCrop(original)).width, 0.0)
    }

    @Test fun endpointSelectionUsesScaledAreaAndKeepsTheSourceOnTies() {
        val from = PopupPortalBounds(0.0, 0.0, 80.0, 20.0)
        val to = PopupPortalBounds(0.0, 0.0, 20.0, 80.0)
        assertFalse(fluidPopupContentGrows(from, to))
        assertFalse(fluidPopupContentGrows(from, to.copy(height = 79.9)))
        assertTrue(fluidPopupContentGrows(from, to.copy(height = 80.1)))
        assertFalse(fluidPopupContentGrows(from, to.copy(height = Double.NaN)))
        assertEquals(PopupPortalBounds(60.0, 190.0, 80.0, 20.0), from.centeredAt(100.0, 200.0))
    }

    @Test fun liveApertureUsesLocalInsetsAndAxisSelectedRatios() {
        val aperture = fluidPopupAperture(200.0, 120.0, insets)
        assertEquals(PopupPortalBounds(0.0, 0.0, 208.0, 128.0), aperture)
        assertEquals(104.0 to 64.0, aperture.midX to aperture.midY)
        assertEquals(104.0 to -6.0, aperture.destinationPosition(insets, insets.copy(top = 9.0)))

        assertEquals(2.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 0))
        assertEquals(2.0 to 2.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 1))
        assertEquals(3.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 2))
        assertEquals(1.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, -0.0, 40.0, axis = 0))
        assertEquals(1.0 to 1.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 0.0, axis = 2))
    }

    @Test fun autoAxisUsesRelativeChangeAndItsOrderedTieRule() {
        assertEquals(2, fluidPopupAutoAxis(80.0, 40.0, 120.0, 44.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 40.0, 88.0, 60.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 40.0, 120.0, 60.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 0.0, 120.0, 0.0))
    }

    @Test fun menuPreviewKeepsRolesDirectionAndScaleTolerance() {
        assertEquals(insets, fluidPopupPreviewInsets(3.0, 2.0, 5.0, 6.0, reversed = false))
        assertEquals(insets, fluidPopupPreviewInsets(3.0, 6.0, 5.0, 2.0, reversed = true))
        assertTrue(fluidPopupClipsBounds(fluidPopupPreviewOptions(3, 8)))
        assertFalse(fluidPopupClipsBounds(fluidPopupPreviewOptions(4, 8)))
        assertEquals(12, fluidPopupPreviewOptions(5, 8))
        assertEquals(8, fluidPopupPreviewOptions(6, 8))
        assertFalse(fluidPopupPreviewScaleChanges(1.0, Math.nextUp(1.0)))
        assertTrue(fluidPopupPreviewScaleChanges(1.0, Math.nextUp(Math.nextUp(1.0))))
        assertTrue(fluidPopupPreviewScaleChanges(1.0, Double.NaN))
    }

    @Test fun throughRoleRequestsKeepTheRecoveredIndexOrder() {
        val smaller = PopupPortalBounds(0.0, 0.0, 40.0, 20.0)
        val larger = PopupPortalBounds(0.0, 0.0, 50.0, 30.0)
        val tied = PopupPortalBounds(0.0, 0.0, 20.0, 40.0)
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.From, popupThroughSlots(smaller, larger, fromPhase = true))
        assertEquals(PopupThroughItem.To to PopupThroughItem.Pivot, popupThroughSlots(smaller, larger, fromPhase = false))
        assertEquals(PopupThroughItem.From to PopupThroughItem.Pivot, popupThroughSlots(larger, smaller, fromPhase = true))
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.To, popupThroughSlots(larger, smaller, fromPhase = false))
        assertEquals(PopupThroughItem.From to PopupThroughItem.Pivot, popupThroughSlots(smaller, tied, fromPhase = true))
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.To, popupThroughSlots(smaller, tied, fromPhase = false))
    }

    @Test fun growingOutlineTargetsRoundBeforeConversionAndKeepTheCompanionSeparate() {
        val larger = PopupPortalBounds(0.0, 0.0, 20.0, 20.0)
        val pivot = PopupPortalBounds(-4.5, 11.5, 4.0, 4.0)
        val targets = fluidGrowingOutlineTargets(
            larger, pivot, 4.0,
            toSmallerContainer = { square ->
                assertEquals(PopupPortalBounds(-5.0, 12.0, 4.0, 4.0), square)
                square.copy(x = square.x + 30.0, y = square.y + 20.0, width = 8.0)
            },
            toPivotContainer = { square ->
                assertEquals(PopupPortalBounds(1.0, 11.0, 4.0, 4.0), square)
                square.copy(x = square.x - 10.0, width = 6.0, height = 2.0)
            },
            companionInset = { bounds, inset ->
                assertTrue(bounds === larger)
                assertEquals(0.5, inset, 0.0)
                PopupPortalBounds(3.0, 2.0, 15.0, 15.0)
            },
        )
        val collapsed = FluidOutlineOverride(PopupPortalBounds(25.0, 32.0, 8.0, 4.0), 2.0, identityTransform = true)
        val companion = FluidOutlineOverride(PopupPortalBounds(-9.0, 11.0, 6.0, 2.0), 1.0)
        assertEquals(FluidOutlineStep(collapsed, null), targets.collapsedSource)
        assertEquals(FluidOutlineStep(FluidOutlineOverride(null, 20.0), companion), targets.overrounded)
        assertEquals(FluidOutlineStep(null, companion), targets.fullyExpanded)
        assertTrue(targets.overrounded.companion === targets.fullyExpanded.companion)
    }

    @Test fun growingKickUsesTheLargerAnchorOrThePivotInTheSameRoot() {
        val larger = PopupPortalBounds(0.0, 100.0, 90.0, 200.0)
        val pivot = PopupPortalBounds(0.0, 140.0, 45.0, 20.0)
        assertEquals(40.0, larger.growingKickTarget(pivot, 0.5, null), 0.0)
        assertEquals(-40.0, larger.growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(60.0, larger.growingKickTarget(pivot, 0.75, 0.125), 0.0)
        assertEquals(-20.0, larger.copy(height = 60.0).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-30.0, larger.copy(height = 165.0).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-40.0, larger.copy(height = Double.NaN).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-80.0, larger.growingKickTarget(pivot.copy(y = Double.NaN), 0.5, null), 0.0)
        assertEquals((-0.0).toRawBits(), larger.growingKickTarget(pivot.copy(y = 190.0), 0.5, null).toRawBits())
    }

    @Test fun presentedPlacementUsesLiveInsetsAndAxisSpecificBounds() {
        val apertureInsets = PopupPortalInsets(10.0, 8.0, 6.0, 4.0)
        val itemInsets = PopupPortalInsets(2.0, 3.0, 4.0, 5.0)
        val aperture = fluidPopupAperture(100.0, 60.0, apertureInsets)
        fun placed(axis: Int, reversed: Boolean): PopupPortalPlacement {
            val scale = fluidPopupDestinationScale(100.0, 60.0, 42.0, 34.0, axis)
            return aperture.presentedDestination(50.0, 40.0, scale, axis, apertureInsets, itemInsets, reversed)
        }
        val xBounds = PopupPortalBounds(0.0, 0.0, 50.0, 31.92)
        assertEquals(PopupPortalPlacement(xBounds, 60.38095238095239, 5.238095238095238), placed(1, false))
        assertEquals(PopupPortalPlacement(xBounds, 51.61904761904763, 5.238095238095238), placed(1, true))
        assertEquals(PopupPortalPlacement(PopupPortalBounds(0.0, 0.0, 63.46666666666667, 40.0), 56.0, 0.0), placed(2, false))
        assertEquals(PopupPortalPlacement(PopupPortalBounds(0.0, 0.0, 50.0, 40.0), 56.0, 0.0), placed(0, false))
    }

    @Test fun presentedPlacementKeepsZeroScaleDivision() {
        val aperture = fluidPopupAperture(0.0, 60.0, insets)
        val horizontal = aperture.presentedDestination(50.0, 40.0, 0.0 to 0.0, 1, insets, insets, false)
        assertEquals(Double.POSITIVE_INFINITY, horizontal.bounds.height, 0.0)
        assertTrue(horizontal.x.isNaN())
        val vertical = aperture.presentedDestination(50.0, 40.0, -0.0 to -0.0, 2, insets, insets, false)
        assertEquals(Double.NEGATIVE_INFINITY, vertical.bounds.width, 0.0)
        assertEquals(0.0.toRawBits(), vertical.y.toRawBits())
    }

    @Test fun firstMatchingDestinationKeepsItsLayerAndProgressWhenMovedToTheTail() {
        data class Destination(val id: String, val layer: Any = Any(), val progress: Any = Any(), var payload: String)
        val first = Destination("match", payload = "old-first")
        val duplicate = Destination("match", payload = "old-second")
        val destinations = mutableListOf(first, duplicate)
        val calls = mutableListOf<String>()
        val keptLayer = first.layer
        val keptProgress = first.progress
        val match = updateFluidPopupDestination(destinations, "new", { current, _ -> current.id == "match" },
            { error("An existing destination must be reused") },
            { current ->
                assertTrue(current === destinations.last())
                assertTrue(current.layer === keptLayer)
                calls += current.payload
            },
            { current, payload -> current.payload = payload },
            { error("The opacity setup is for new destinations only") },
        )
        assertTrue(match === first)
        assertTrue(match.progress === keptProgress)
        assertEquals(listOf("old-first"), calls)
        assertEquals(listOf("old-second", "new"), destinations.map { it.payload })
        val progress = mutableListOf<Pair<Any, Double>>()
        destinations.requestFluidDestinationProgress(match, { a, b -> a === b }) { destination, target ->
            progress += destination.progress to target
        }
        assertEquals(listOf(duplicate.progress to 0.0, keptProgress to 1.0), progress)
    }

    @Test fun newDestinationIsPreparedBeforeAppendAndHiddenOnlyWithAnOlderOne() {
        val destinations = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        for (item in listOf("first", "second")) {
            val added = updateFluidPopupDestination(destinations, item, String::equals,
                {
                    assertFalse(item in destinations)
                    item
                },
                { error("There is no matching item") },
                { _, _ -> error("There is no existing item to replace") },
                {
                    assertEquals(it, destinations.last())
                    hidden += it
                },
            )
            assertEquals(item, added)
        }
        assertEquals(listOf("first", "second"), destinations)
        assertEquals(listOf("second"), hidden)
    }

    @Test fun ordinaryLayoutCapturesTheTopAndUsesItsEqualityForProgress() {
        data class Destination(val id: Int, val label: String)
        val first = Destination(1, "first")
        val middle = Destination(2, "middle")
        val top = Destination(1, "top")
        val retained = listOf(first, middle, top)
        assertNull(emptyList<Destination>().fluidLayoutTop(hasOverrideBounds = false))
        assertNull(retained.fluidLayoutTop(hasOverrideBounds = true))
        assertTrue(retained.fluidLayoutTop(hasOverrideBounds = false) === top)
        val targets = mutableListOf<Pair<String, Double>>()
        retained.requestFluidDestinationProgress(top, { a, b -> a.id == b.id }) { destination, target ->
            targets += destination.label to target
        }
        assertEquals(listOf("first" to 1.0, "middle" to 0.0, "top" to 1.0), targets)
    }

    @Test fun layoutRefreshClearsDirtyBeforeUpdatingTheStoredDestinations() {
        val retained = listOf("from", "to")
        val calls = mutableListOf<String>()
        retained.refreshFluidDestinationSizes(false, { error("Already clean") }, { error("Already clean") }, { error("Already clean") })
        retained.refreshFluidDestinationSizes(true, { calls += "clear" }, { calls += it }, { calls += "model" })
        assertEquals(listOf("clear", "from", "to", "model"), calls)
    }

    @Test fun incomingAxisUsesTheSnapshotGuardsAndOnlyFallsBackToTheExistingTail() {
        val zero = PopupPortalInsets(-0.0, 0.0, 0.0, 0.0)
        val target = { PopupPortalBounds(0.0, 0.0, 120.0, 60.0) }
        val skip = { error("The geometry read must be skipped") }
        assertNull(fluidPopupIncomingAxis(0, 0, zero, skip, skip, skip))
        assertNull(fluidPopupIncomingAxis(1, 2, zero, skip, skip, skip))
        assertEquals(1, fluidPopupIncomingAxis(1, 0, insets, skip, skip, skip))
        assertEquals(1, fluidPopupIncomingAxis(1, 0, zero, target, {
            PopupPortalBounds(0.0, 0.0, 120.0, 40.0)
        }, skip))
        assertEquals(2, fluidPopupIncomingAxis(1, 0, zero, target, { null }) {
            PopupPortalBounds(0.0, 0.0, -80.0, 60.0)
        })
    }

    @Test fun rasterRequestUsesTheRetainedScaleOnlyWithBoxMinification() {
        assertEquals(FluidRasterRequest(true, 1.75, "box"), fluidPopupRasterRequest(true, 1.75))
        assertEquals(FluidRasterRequest(false, null, "linear"), fluidPopupRasterRequest(false, Double.NaN))
    }

    @Test fun layoutAxisReadsPresentedBoundsOnlyForZeroInsetsAndADifferentPreference() {
        val zero = PopupPortalInsets(-0.0, 0.0, 0.0, 0.0)
        val item = PopupPortalBounds(0.0, 0.0, 120.0, 44.0)
        assertNull(fluidPopupLayoutAxis(insets, 2, 1, item) { error("Insets block axis selection") })
        assertNull(fluidPopupLayoutAxis(zero, 2, 2, item) { error("The preferred axis is already current") })
        assertEquals(2, fluidPopupLayoutAxis(zero, 2, 1, item) { PopupPortalBounds(0.0, 0.0, 80.0, 40.0) })
        assertEquals(1, fluidPopupLayoutAxis(zero, 2, 1, item) { null })
    }

    @Test fun layoutSizeReadsTheModelInsetsAfterWritingTheTopInsets() {
        val calls = mutableListOf<String>()
        val model = insets.copy(leading = 4.0, bottom = 7.0)
        requestFluidContentSize(
            insets,
            {
                assertEquals(insets, it)
                calls += "insets"
            },
            {
                calls += "bounds"
                backing
            },
            {
                calls += "model"
                model
            },
            { bounds, modelInsets ->
                val crop = requireNotNull(modelInsets.signedCrop(bounds))
                crop.width to crop.height
            },
            { calls += "width=$it" },
            { calls += "height=$it" },
        )
        assertEquals(listOf("insets", "bounds", "model", "width=70.0", "height=40.0"), calls)
    }
}
