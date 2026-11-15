package io.github.anastaci1a.localica

import android.view.Window
import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.GlassPopupFlight
import io.github.anastaci1a.localica.ui.PopupEvent.*
import io.github.anastaci1a.localica.ui.PopupPresenter
import io.github.anastaci1a.localica.ui.engine.PopupTakeover
import org.junit.Assert.*
import org.junit.Test

/** #920 C1 and window receipts on the pure model, with real dialog windows. */
class PopupStateWindowTest {
    private fun GlassPopupFlight.releasing(window: Window) = testRun(Admitted, OfferReady, OfferTaken, window = window).testStep(HomeReturns)
    private val GlassPopupFlight.at get() = state.takeover to state.presenter

    /** One frame by the draw rules: the home projects while prepared, else releases; the window draws while not handed off. */
    private fun GlassPopupFlight.frame(window: Window, homeFirst: Boolean): Pair<Boolean, Boolean> {
        var home = false; var drawn = false
        val homeDraw = { if (!prepared) receipt(OfferReleased); home = prepared; if (prepared) receipt(OfferTaken) }
        val windowDraw = { drawn = !handedOff; if (drawn) receipt(WindowDrew, window) }
        if (homeFirst) { homeDraw(); windowDraw() } else { windowDraw(); homeDraw() }
        return home to drawn
    }

    @Test fun aSiblingsWindowDrawNeverReleasesAnotherPresentation() {
        val anchor = GlassAnchor()
        val (wa, wb) = newTestWindow() to newTestWindow()
        val a = GlassPopupFlight(anchor).releasing(wa)
        val b = GlassPopupFlight(anchor).releasing(wb)
        assertFalse("A's window is not B's", b.receipt(WindowDrew, wa))
        assertFalse("no release before B's window drew", b.receipt(OfferReleased))
        assertTrue(b.prepared)
        assertTrue(b.receipt(WindowDrew, wb))
        assertEquals(PopupTakeover.InWindow to PopupPresenter.Reclaimed, b.at)
        assertFalse(b.prepared)
        assertTrue(b.receipt(OfferReleased))
        assertEquals(PopupTakeover.InWindow to PopupPresenter.Window, b.at)
        assertEquals("A is untouched", PopupTakeover.InWindow to PopupPresenter.Releasing, a.at)
    }

    @Test fun aReopenedPresentationTakesItsWindowsDrawOnlyAfterAdmission() {
        val w = newTestWindow()
        val flight = GlassPopupFlight().releasing(w)
        val first = flight.state.gen
        assertTrue(flight.advance(first, Gone))
        flight.testRun(window = w)
        assertFalse("Admitting has no window", flight.receipt(WindowDrew, w))
        assertTrue(flight.advance(flight.state.gen, Admitted, w))
        flight.testStep(OfferReady, OfferTaken, HomeReturns, window = w)
        assertFalse("a writer of the earlier open is refused", flight.advance(first, WindowDrew, w))
        assertTrue(flight.receipt(WindowDrew, w))
        assertEquals(PopupTakeover.InWindow to PopupPresenter.Reclaimed, flight.at)
    }

    @Test fun everyReleaseFrameHasThePopupAndAtMostOneHasItTwice() {
        for (homeFirst in listOf(true, false)) {
            val w = newTestWindow()
            val flight = GlassPopupFlight().testRun(Admitted, OfferReady, OfferTaken, window = w)
            assertEquals("$homeFirst: taken, the home alone draws", true to false, flight.frame(w, homeFirst))
            flight.testStep(HomeReturns)
            assertTrue("$homeFirst: the home projects until the window drew", flight.prepared)
            val frames = List(3) { flight.frame(w, homeFirst) }
            assertTrue("$homeFirst: $frames", frames.all { it.first || it.second })
            assertTrue("$homeFirst: $frames", frames.count { it.first && it.second } <= 1)
            assertEquals("$homeFirst", PopupTakeover.InWindow to PopupPresenter.Window, flight.at)
        }
    }

    @Test fun windowReceiptsNamingAForeignOrNoWindowAreRefused() {
        val (w, foreign) = newTestWindow() to newTestWindow()
        val flight = GlassPopupFlight().testRun(Admitted, OfferReady, window = w)
        for (e in listOf(SampleLost, WindowGone, WindowDrew)) {
            assertFalse("$e foreign", flight.receipt(e, foreign))
            assertFalse("$e none", flight.receipt(e))
        }
        assertSame(w, flight.state.window)
        assertEquals(PopupTakeover.InWindow to PopupPresenter.Offered, flight.at)
        assertTrue(flight.receipt(WindowGone, w))
        assertNull(flight.state.window)
        assertEquals("the identity clear keeps the presenter", PopupPresenter.Offered, flight.state.presenter)
    }

    /** Receipt signature: GlassProjectionReceipt.offset = origin - the home's current origin, except while Taken (latched at the hand-off frame). */
    @Test fun theProjectionTracksAMovingHomeExceptWhileTaken() {
        val w = newTestWindow()
        val flight = GlassPopupFlight().testRun(Admitted, OfferReady, window = w).apply { origin = Offset(100f, 200f) }
        assertEquals("offered", Offset(90f, 180f), flight.projectionOffset(Offset(10f, 20f)))
        assertEquals("offered, moved", Offset(70f, 180f), flight.projectionOffset(Offset(30f, 20f)))
        flight.testStep(OfferTaken)
        assertEquals("taken: latched", Offset(70f, 180f), flight.projectionOffset(Offset(50f, 20f)))
        flight.testStep(HomeReturns)
        assertEquals("releasing: tracks, as the window draws it", Offset(40f, 180f), flight.projectionOffset(Offset(60f, 20f)))
    }

    @Test fun disposalAfterASameWindowReopenResetsOnlyTheCurrentOpen() {
        val w = newTestWindow()
        val flight = GlassPopupFlight().testRun(Admitted, OfferReady, OfferTaken, AtHome, Returned, window = w)
        val first = flight.state.gen
        flight.testRun(Admitted, OfferReady, OfferTaken, window = w)
        assertFalse("an open-scoped writer of the earlier open is refused", flight.advance(first, HomeLeft))
        assertTrue(flight.advance(flight.state.gen, HomeLeft))
        assertEquals(PopupTakeover.InWindow to PopupPresenter.Window, flight.at)
        assertTrue(flight.advance(flight.state.gen, WindowGone, w))
        assertNull(flight.state.window)
        assertEquals(PopupTakeover.InWindow, flight.takeover)
    }

    @Test fun replacementClearsEveryPresentersAdmissionAndSurvivesASecondReplacement() {
        for (restoring in listOf(false, true)) for (presenter in PopupPresenter.entries) {
            val old = newTestWindow(); val next = newTestWindow()
            val flight = GlassPopupFlight().testRun(Admitted, window = old)
            if (presenter != PopupPresenter.Window) flight.testStep(OfferReady, window = old)
            if (presenter in listOf(PopupPresenter.Taken, PopupPresenter.Releasing, PopupPresenter.Reclaimed)) flight.testStep(OfferTaken)
            if (presenter in listOf(PopupPresenter.Releasing, PopupPresenter.Reclaimed)) flight.testStep(HomeReturns)
            if (presenter == PopupPresenter.Reclaimed) flight.testStep(WindowDrew, window = old)
            if (restoring) flight.testStep(AtHome)
            val basis = flight.basis; val gen = flight.gen
            assertTrue(flight.admitted(old))
            flight.testStep(WindowReplaced)
            assertEquals(PopupTakeover.Admitting to null, flight.at)
            assertNull(flight.window)
            assertFalse("old admission never authorizes either window", flight.admitted(old) || flight.admitted(next))
            assertFalse(flight.prepared || flight.handedOff || flight.wasProjected)
            assertSame(basis, flight.basis); assertEquals(gen, flight.gen)
            assertTrue("replacement while Admitting", flight.advance(gen, WindowReplaced))
            assertTrue(flight.advance(gen, Admitted, next))
            assertFalse("late old detach", flight.receipt(WindowGone, old))
            assertTrue(flight.admitted(next)); assertFalse(flight.admitted(old))
        }
    }
}
