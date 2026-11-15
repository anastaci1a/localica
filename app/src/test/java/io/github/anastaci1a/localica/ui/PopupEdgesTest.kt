package io.github.anastaci1a.localica.ui

import android.app.Dialog
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateOf
import io.github.anastaci1a.localica.ui.PopupEvent.*
import io.github.anastaci1a.localica.ui.PopupPresenter.*
import io.github.anastaci1a.localica.ui.engine.PopupTakeover
import io.github.anastaci1a.localica.ui.engine.PopupTakeover.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #920 step 3: the one transition table (SPEC section 3), the one writer's open generation, and sibling presentations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupEdgesTest {
    private val window by lazy { checkNotNull(Dialog(RuntimeEnvironment.getApplication()).window) }
    private val basis = SamplingBasis(null, Rect.Zero, null, false)
    private val states = listOf(Closed to null, Admitting to null) + listOf(InWindow, Restoring).flatMap { t -> PopupPresenter.entries.map { t to it } }

    @Test fun everyStateIsReachableAndPresentersOnlyLiveInAWindow() {
        val targets = PopupEdges.values.toSet()
        for ((t, p) in states) if (t != Closed && t != Admitting) assertTrue("$t $p", (t to p) in targets)
        for ((t, p) in targets) assertEquals("$t $p", t >= InWindow, p != null)
        assertTrue(PopupEdges.keys.none { it.first == Closed })
    }

    @Test fun everySpecRow() {
        val rows = mapOf(
            Triple(Admitting, null, Admitted) to (InWindow to Window), // T2
            Triple(Admitting, null, Hide) to (Closed to null), // T3
            Triple(Admitting, null, Gone) to (Closed to null), // T17
            Triple(InWindow, Window, OfferReady) to (InWindow to Offered), // T7
            Triple(InWindow, Offered, OfferTaken) to (InWindow to Taken), // T8
            Triple(InWindow, Offered, SampleLost) to (InWindow to Window),
            Triple(InWindow, Taken, SampleLost) to (InWindow to Window),
            Triple(InWindow, Window, AtHome) to (Restoring to Window), // T9
            Triple(Restoring, Taken, Returned) to (Closed to null), // T10
            Triple(Restoring, Window, Show) to (InWindow to Window), // T11
            Triple(InWindow, Offered, HomeReturns) to (InWindow to Window), // T12
            Triple(InWindow, Taken, HomeReturns) to (InWindow to Releasing), // T13, T15
            Triple(InWindow, Releasing, WindowDrew) to (InWindow to Reclaimed), // C1
            Triple(Restoring, Releasing, WindowDrew) to (Restoring to Reclaimed),
            Triple(InWindow, Reclaimed, OfferReleased) to (InWindow to Window), // T14
            Triple(Restoring, Taken, HomeLeft) to (Restoring to Window),
            Triple(InWindow, Offered, WindowGone) to (InWindow to Offered),
            Triple(Restoring, Offered, Gone) to (Closed to null)) // T17
        for ((key, to) in rows) assertEquals("$key", to, PopupEdges[key])
        for (key in listOf(Triple(InWindow, Window, SampleLost), Triple(InWindow, Window, Show), Triple(InWindow, Releasing, OfferTaken), Triple(InWindow, Releasing, OfferReleased), Triple(InWindow, Taken, WindowDrew), Triple(InWindow, Window, WindowDrew),
            Triple(Admitting, null, HomeLeft), Triple<PopupTakeover, PopupPresenter?, PopupEvent>(Closed, null, Gone))) assertNull("$key", PopupEdges[key])
        for (p in PopupPresenter.entries) {
            assertEquals(Restoring to p, PopupEdges[Triple(InWindow, p, AtHome)])
            assertEquals(Closed to null, PopupEdges[Triple(Restoring, p, Returned)])
            assertNull("T11 uses Show, never admission", PopupEdges[Triple(Restoring, p, Admitted)])
        }
    }

    @Test fun aWriterOfAnEarlierOpenIsRefused() {
        val flight = GlassPopupFlight()
        assertTrue(flight.open(basis))
        assertFalse("one open at a time", flight.open(basis))
        val first = flight.state.gen
        assertFalse("intent only: Show while admitting is ignored", flight.advance(first, Show))
        assertTrue(flight.advance(first, Hide))
        assertEquals(PopupState(gen = first), flight.state)
        assertTrue(flight.open(basis))
        assertFalse("a writer of the earlier open never writes", flight.advance(first, Gone))
        assertEquals(Admitting, flight.takeover)
        assertTrue(flight.advance(flight.state.gen, Gone))
    }

    @Test fun windowReceiptsMustNameAWindow() {
        val flight = GlassPopupFlight()
        assertTrue(flight.open(basis))
        assertFalse("no takeover without its admitted window", flight.advance(flight.state.gen, Admitted))
        assertEquals(Admitting, flight.takeover)
        assertNull(flight.window)
        assertTrue(flight.advance(flight.state.gen, Admitted, window))
        for (e in PopupEvent.entries.filter { it.windowReceipt }) assertFalse("$e without a window", flight.receipt(e))
        assertEquals(InWindow to Window, flight.state.takeover to flight.state.presenter)
    }

    @Test fun closingOrDisposingASiblingNeverChangesTheAdmittedPresentation() {
        for (close in listOf(Hide, Gone)) {
            val anchor = GlassAnchor()
            val a = GlassPopupFlight(anchor)
            val b = GlassPopupFlight(anchor)
            assertTrue(a.open(basis))
            assertTrue(b.open(basis))
            assertTrue(b.advance(b.state.gen, Admitted, window))
            assertSame("the newer presentation is admitted", b, anchor.popup)
            val before = b.state
            assertTrue(a.advance(a.state.gen, close))
            assertSame("$close: B stays admitted", b, anchor.popup)
            assertEquals("$close: B's state is unchanged", before, b.state)
            assertEquals(InWindow, anchor.takeover)
        }
    }

    @Test fun aSiblingsReceiptsNeverChangeAnother() {
        val anchor = GlassAnchor()
        val a = GlassPopupFlight(anchor)
        val b = GlassPopupFlight(anchor)
        for (f in listOf(a, b)) { assertTrue(f.open(basis)); assertTrue(f.advance(f.state.gen, Admitted, window)) }
        val before = b.state
        for (e in listOf(OfferTaken, OfferReleased, HomeLeft, HomeReturns)) a.receipt(e)
        assertEquals(before, b.state)
    }
    @Test fun removingAHomeRetiresAllItsFlightsAtTheEvent() {
        val home = GlassPopupHome(mutableStateOf(false))
        val flights = List(2) { GlassPopupFlight().apply { assertTrue(open(this@PopupEdgesTest.basis)); assertTrue(advance(state.gen, Admitted, this@PopupEdgesTest.window)) } }
        home.flights.addAll(flights)
        home.gone()
        assertFalse(home.mounted)
        for (flight in flights) assertEquals(PopupState(gen = flight.state.gen), flight.state)
        assertEquals("registration cleanup is owned by disposal", flights, home.flights.toList())
    }
}
