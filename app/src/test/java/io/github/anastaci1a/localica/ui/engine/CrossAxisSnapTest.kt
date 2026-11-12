package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** #854 T7: the cross-axis snap variant, its kind table (section 2) and the Auto aspect rule (section 1.6). */
class CrossAxisSnapTest {
    private val model = StockPopupMotion
    private val crossOpen = model.law(PopupKind.BottomMenu, PopupDirection.Open, true); private val crossClose = model.law(PopupKind.BottomMenu, PopupDirection.Close, true)
    private val open = model.open; private val close = model.close
    private val autoModel = model.copy(variants = model.variants + (PopupKind.Menu to PopupMotionVariant.Auto))
    private fun z(l: SpringLaw) = l.zeta.toDouble(); private fun w(l: SpringLaw) = l.omega.toDouble()
    /** A menu opening from a trigger inside its corner: the main axis is y (travel 700), the cross edges are the x edges (left moves 300, right stays). */
    private val menu = ExpansionBasis(Rect(300f, 700f, 400f, 800f), Rect(0f, 0f, 400f, 700f), 1f)
    private fun crossEdges(rig: PopupRig) = (if (rig.basis.edgeIndex % 2 == 0) listOf(1, 3) else listOf(0, 2)).filter { rig.end[it] != rig.start[it] }
    /** The fraction of its travel an edge has covered. */
    private fun fraction(rig: PopupRig, i: Int, stamp: FrameStamp) = (rig.edge(i, stamp) - rig.start[i]) / (rig.end[i] - rig.start[i])

    @Test fun kindToVariantTableFollowsSectionTwo() {
        assertEquals(mapOf(PopupKind.Menu to PopupMotionVariant.Base, PopupKind.RowMenu to PopupMotionVariant.Base, PopupKind.BottomMenu to PopupMotionVariant.CrossAxisSnap,
            PopupKind.Dialog to PopupMotionVariant.Base, PopupKind.SelectionPill to PopupMotionVariant.Base, PopupKind.Legacy to PopupMotionVariant.Legacy), model.variants)
        for (kind in PopupKind.entries) EngineHarness().use { h ->
            val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false).also { it.usePopupLaw(model, kind) }
            val variant = model.variant(kind)
            assertEquals("$kind", variant != PopupMotionVariant.Legacy, owner.popupLaw != null)
            assertEquals("$kind", variant, owner.popupLaw?.variant ?: PopupMotionVariant.Legacy)
            assertEquals("$kind has a cross phase only when it can snap", variant == PopupMotionVariant.CrossAxisSnap, owner.crossPhase() != null)
            owner.dispose()
        }
        // Auto owners also carry a cross phase.
        EngineHarness().use { h -> val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false).also { it.usePopupLaw(autoModel, PopupKind.Menu) }; assertNotNull(owner.crossPhase()); owner.dispose() }
    }

    @Test fun bottomMenuCrossEdgesReachNinetyPercentByTheCrossLawWhileMainEdgesFollowTheOpenLaw() {
        val t90 = SpringOracle.crossing(.9, w(crossOpen), z(crossOpen))
        assertTrue("within one 30 fps frame", t90 <= 1.0 / 30)
        for (hz in listOf(60, 120)) EngineHarness().use { h ->
            h.setContext(hz)
            val rig = PopupRig(h, menu, PopupKind.BottomMenu); val main = rig.basis.edgeIndex; val cross = crossEdges(rig)
            assertTrue(cross.isNotEmpty())
            rig.owner.target(true); val start = h.clock.stampNow
            val at = stampAt(start, t90)
            for (i in cross) assertEquals("cross edge $i at the cross t90", .9, fraction(rig, i, at), 1e-5)
            val mainFraction = fraction(rig, main, at)
            assertEquals("main edge follows the open law at the cross t90", SpringOracle.fromRest(w(open), z(open), t90), mainFraction, 1e-5)
            assertTrue("and is far from done: $mainFraction", mainFraction < .5)
            for (k in 1..30) {
                val t = k * .004; val s = stampAt(start, t)
                for (i in cross) assertEquals("cross edge $i t=$t", SpringOracle.fromRest(w(crossOpen), z(crossOpen), t), fraction(rig, i, s), 1e-5)
                assertEquals("main edge t=$t", SpringOracle.fromRest(w(open), z(open), t), fraction(rig, main, s), 1e-5)
                assertEquals("the published phase is the main phase", SpringOracle.fromRest(w(open), z(open), t), rig.owner.main.rawValue(s), 1e-9)
            }
            h.advance(5.0); assertTrue("retirement waits for the cross phase too", rig.owner.retirementReady); rig.owner.dispose()
        }
    }

    @Test fun theCrossAxisStaysFastOnClose() = EngineHarness().use { h ->
        h.setContext(120)
        val rig = PopupRig(h, menu, PopupKind.BottomMenu, expanded = true); val main = rig.basis.edgeIndex; val cross = crossEdges(rig)
        assertTrue(cross.isNotEmpty())
        rig.owner.target(false); val start = h.clock.stampNow
        for (k in 1..30) {
            val t = k * .004; val s = stampAt(start, t)
            for (i in cross) assertEquals("cross edge $i t=$t", SpringOracle.at(1.0, 0.0, 0.0, w(crossClose), z(crossClose), t).first, fraction(rig, i, s), 1e-5)
            assertEquals("main edge t=$t", SpringOracle.at(1.0, 0.0, 0.0, w(close), z(close), t).first, fraction(rig, main, s), 1e-5)
        }
        rig.owner.dispose()
    }

    /**
     * Whether the cross (bottom) edge of a tall-and-narrow home opening into a [aspect] (height / width) panel follows the cross law. The larger
     * travel is on x (index 2), so the cross edges are the y edges. Without [crossTravel] the bottom edge does not move.
     */
    private fun snaps(kind: PopupKind, m: PopupMotionModel, aspect: Float, crossTravel: Boolean): Boolean = EngineHarness().use { h ->
        h.setContext(120)
        val width = 100f; val height = aspect * width; val homeHeight = if (crossTravel) height + (if (aspect < 1.7f) 11f else -50f) else height
        val rig = PopupRig(h, ExpansionBasis(Rect(0f, 0f, 10f, homeHeight), Rect(0f, 0f, width, height), 1f), kind, model = m)
        assertEquals("main axis is x", 2, rig.basis.edgeIndex)
        rig.owner.target(true); val start = h.clock.stampNow
        val t = SpringOracle.crossing(.9, w(crossOpen), z(crossOpen)); val s = stampAt(start, t)
        val result = if (rig.end[3] == rig.start[3]) {
            // No cross travel: nothing to snap; the edge stays bit-constant.
            assertEquals(rig.start[3], rig.edge(3, s), 0.0); false
        } else {
            val f = fraction(rig, 3, s)
            val snapped = abs(f - SpringOracle.fromRest(w(crossOpen), z(crossOpen), t)) < 1e-5; val main = abs(f - SpringOracle.fromRest(w(open), z(open), t)) < 1e-5
            assertTrue("the cross edge follows exactly one law: fraction $f", snapped != main); snapped
        }
        assertEquals("main edge follows the open law", SpringOracle.fromRest(w(open), z(open), t), fraction(rig, 2, s), 1e-5)
        rig.owner.dispose(); result
    }

    @Test fun autoSnapsOnlyAboveTheAspectThresholdAndWithCrossTravel() {
        val threshold = model.snapAspect
        assertEquals("1.39 is below the threshold", false, snaps(PopupKind.Menu, autoModel, threshold - .01f, true))
        assertEquals("1.41 is above it", true, snaps(PopupKind.Menu, autoModel, threshold + .01f, true))
        assertEquals("2.0 is above it", true, snaps(PopupKind.Menu, autoModel, 2.0f, true))
        assertEquals("1.41 with zero cross travel has nothing to snap", false, snaps(PopupKind.Menu, autoModel, threshold + .01f, false))
        assertEquals("2.0 with zero cross travel has nothing to snap", false, snaps(PopupKind.Menu, autoModel, 2.0f, false))
        // Declared kinds do not read the aspect: BottomMenu snaps at 0.8 (the measured e2 case) and at 2.0; Menu, RowMenu and Dialog never do.
        assertEquals(true, snaps(PopupKind.BottomMenu, model, .8f, true)); assertEquals(true, snaps(PopupKind.BottomMenu, model, 2.0f, true))
        for (kind in listOf(PopupKind.Menu, PopupKind.RowMenu, PopupKind.Dialog)) assertEquals("$kind", false, snaps(kind, model, 2.0f, true))
    }
}
