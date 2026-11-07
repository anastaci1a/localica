package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.engine.FocusEffect.*
import io.github.anastaci1a.localica.ui.engine.FocusEvent.*
import io.github.anastaci1a.localica.ui.engine.FocusState.*
import org.junit.Assert.*
import org.junit.Test

/** #640 §1.1: the hold-to-focus transition table, one test per rule plus the exhaustive state × event matrix. */
class FocusReducerTest {
    private val timing = FocusTiming(deadZoneMs = 100, liftMs = 400, slopPx = 8f)
    private val reducer = FocusReducer(timing)
    private val origin = Offset(100f, 200f)
    private val key = "song-1"

    private class Run(val reducer: FocusReducer, var state: FocusState = Idle) {
        val effects = mutableListOf<FocusEffect>()
        fun send(vararg events: FocusEvent): Run { for (e in events) state = reducer.reduce(state, e, effects); return this }
    }

    private fun run(state: FocusState = Idle) = Run(reducer, state)
    private fun down(t: Long = 0, mutable: Boolean = false, key: Any? = this.key, admitted: Boolean = true, flinging: Boolean = false) = Down(t, key, origin, mutable, admitted, flinging)
    private fun move(t: Long, dx: Float = 0f, dy: Float = 0f, pointers: Int = 1) = Move(t, origin + Offset(dx, dy), pointers)
    private fun up(t: Long, dx: Float = 0f, dy: Float = 0f) = Up(t, origin + Offset(dx, dy))

    // Tap, scroll yield, cancel before the lift

    @Test fun shortPressIsATapWithNoHaptic() {
        val r = run().send(down(), Tick(50), up(80))
        assertEquals(listOf(Tap(key), Settle), r.effects)
        assertEquals(Settling, r.state)
    }

    @Test fun releaseJustBeforeTheLiftPointIsStillATap() {
        val r = run().send(down(), Tick(300), up(399))
        assertEquals(listOf(Tap(key), Settle), r.effects)
    }

    @Test fun movementBeyondSlopBeforeTheLiftYieldsToTheScroll() {
        val r = run().send(down(), move(50, dx = 3f), move(120, dy = 9f))
        assertEquals(listOf(YieldScroll, Settle), r.effects)
        assertEquals(Settling, r.state)
        // The scroll owns the rest of the gesture: later events change nothing.
        r.send(move(200, dy = 40f), Tick(500), up(600, dy = 40f))
        assertEquals(listOf(YieldScroll, Settle), r.effects)
        assertEquals(Settling, r.state)
    }

    @Test fun subSlopJitterIsIgnoredAndNeverConsumes() {
        val r = run().send(down(), move(50, dx = 4f), move(100, dx = -5f, dy = 5f), move(200, dy = 7.9f))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state is Pressing)
        assertFalse(r.state.ownsPointer)
    }

    @Test fun slopIsMeasuredFromTheDownPositionOnEitherAxis() {
        assertEquals(listOf(YieldScroll, Settle), run().send(down(), move(10, dx = 8.1f)).effects)
        assertEquals(listOf(YieldScroll, Settle), run().send(down(), move(10, dy = -8.1f)).effects)
        assertTrue(run().send(down(), move(10, dx = 5f, dy = 5f)).effects.isEmpty()) // 7.07 < 8
    }

    @Test fun secondPointerBeforeTheLiftCancels() {
        val r = run().send(down(), move(150, pointers = 2))
        assertEquals(listOf(Settle), r.effects)
        assertEquals(Settling, r.state)
    }

    @Test fun pointerCancelBeforeTheLiftSettlesWithoutATap() {
        assertEquals(listOf(Settle), run().send(down(), Cancel(200)).effects)
    }

    @Test fun flingingListOnlyStopsTheFling() {
        val r = run().send(down(flinging = true), up(80))
        assertTrue(r.effects.isEmpty())
        assertEquals(Idle, r.state)
    }

    @Test fun downOutsideAnyItemIsIgnored() {
        assertEquals(Idle, run().send(down(key = null), up(80)).state)
    }

    // Lift

    @Test fun liftHappensAtTheSystemDelayWithOneHaptic() {
        val r = run().send(down(), Tick(399))
        assertTrue(r.effects.isEmpty())
        r.send(Tick(400))
        assertEquals(listOf(Haptic), r.effects)
        assertTrue(r.state is Lifted)
        assertTrue(r.state.ownsPointer)
        r.send(Tick(900), move(950, dx = 2f))
        assertEquals("exactly one haptic", 1, r.effects.count { it == Haptic })
    }

    @Test fun holdProgressIsZeroInTheDeadZoneLinearToTheLiftThenOne() {
        val pressing = run().send(down()).state
        assertEquals(0f, reducer.holdTarget(pressing, 0), 0f)
        assertEquals(0f, reducer.holdTarget(pressing, 100), 0f)
        assertEquals(.5f, reducer.holdTarget(pressing, 250), 1e-6f)
        assertEquals(1f, reducer.holdTarget(pressing, 400), 0f)
        assertEquals(1f, reducer.holdTarget(pressing, 1000), 0f)
        assertEquals(1f, reducer.holdTarget(run().send(down(), Tick(400)).state, 401), 0f)
        assertEquals(0f, reducer.holdTarget(Settling, 500), 0f)
        assertEquals(0f, reducer.holdTarget(Idle, 500), 0f)
        assertEquals(400L, reducer.liftAt(pressing))
        assertEquals(100L, reducer.deadZoneEnd(pressing))
        assertNull(reducer.liftAt(Idle))
    }

    @Test fun anEventStampedAfterTheLiftPointLiftsBeforeItApplies() {
        // The finger released at 420 ms without moving: the lift and the card land in the same reduction.
        val r = run().send(down(), up(420))
        assertEquals(listOf(Haptic, OpenCard(key)), r.effects)
        assertEquals(CardOpening(key), r.state)
    }

    @Test fun releaseAfterLiftWithoutMovementOpensTheCard() {
        val r = run().send(down(), Tick(400), move(500, dx = 3f), up(600, dx = 3f))
        assertEquals(listOf(Haptic, OpenCard(key)), r.effects)
        r.send(CardPresented(616))
        assertEquals(CardOpen(key), r.state)
        r.send(Dismiss(2000))
        assertEquals(listOf(Haptic, OpenCard(key), CloseCard), r.effects)
        assertEquals(CardClosing(key), r.state)
        r.send(Settled(2400))
        assertEquals(Idle, r.state)
    }

    @Test fun releaseAfterLiftInSelectionModeOpensTheCardForTheHeldItem() {
        // Selection mode is not an input to the reducer: tap still toggles (Tap), hold still lifts and cards (judgment call §1.1).
        assertEquals(listOf(Tap(key), Settle), run().send(down(mutable = true), up(90)).effects)
        assertEquals(listOf(Haptic, OpenCard(key)), run().send(down(mutable = true), Tick(400), up(700)).effects)
    }

    // Slop latch after the lift

    @Test fun immutableListMovementBeyondSlopLatchesTheCardOffAndFollows() {
        val r = run().send(down(), Tick(400), move(500, dy = 20f))
        assertEquals(listOf(Haptic), r.effects)
        assertEquals(Following(key, origin, 0, origin + Offset(0f, 20f)), r.state)
        assertTrue(r.state.ownsPointer)
        // Returning inside slop does not re-arm the card.
        r.send(move(600, dy = 1f), up(700, dy = 1f))
        assertEquals(listOf(Haptic, Settle), r.effects)
        assertEquals(Settling, r.state)
    }

    @Test fun immutableReleaseBeyondSlopWithoutAnObservedMoveSpringsBackWithoutACard() {
        assertEquals(listOf(Haptic, Settle), run().send(down(), Tick(400), up(500, dy = 30f)).effects)
    }

    @Test fun mutableListMovementBeyondSlopBeginsTheKeyedDrag() {
        val r = run().send(down(mutable = true), Tick(400), move(450, dy = 5f), move(500, dy = 12f))
        assertEquals(listOf(Haptic, BeginDrag(key, origin)), r.effects)
        assertEquals(Dragging(key), r.state)
        r.send(move(600, dy = 80f), up(700, dy = 80f))
        assertEquals(listOf(Haptic, BeginDrag(key, origin), Settle), r.effects)
        assertEquals(Settling, r.state)
    }

    @Test fun keyedDragCancellingOnItsOwnSettlesTheLift() {
        val r = run().send(down(mutable = true), Tick(400), move(500, dy = 12f), DragEnded(900))
        assertEquals(Settling, r.state)
        assertEquals(Settle, r.effects.last())
    }

    @Test fun secondPointerAfterTheLiftIsIgnoredInFollowingAndDragging() {
        val following = run().send(down(), Tick(400), move(500, dy = 20f), move(520, dy = 30f, pointers = 2))
        assertTrue(following.state is Following)
        val dragging = run().send(down(mutable = true), Tick(400), move(500, dy = 20f), move(520, dy = 30f, pointers = 2))
        assertEquals(Dragging(key), dragging.state)
        val lifted = run().send(down(), Tick(400), move(500, dx = 2f, pointers = 2), up(600))
        assertEquals(listOf(Haptic, OpenCard(key)), lifted.effects)
    }

    @Test fun cancelAfterTheLiftSettlesWithoutACard() {
        assertEquals(listOf(Haptic, Settle), run().send(down(), Tick(400), Cancel(500)).effects)
        assertEquals(listOf(Haptic, Settle), run().send(down(), Tick(400), move(450, dy = 20f), Cancel(500)).effects)
    }

    // Card lifecycle

    @Test fun aDownBeforeTheCardPresentsCutsItButNotAfter() {
        val early = run().send(down(), Tick(400), up(500), down(t = 510))
        assertEquals(listOf(Haptic, OpenCard(key), CloseCard, Settle), early.effects)
        assertEquals(Settling, early.state)
        val late = run().send(down(), Tick(400), up(500), CardPresented(516), down(t = 530))
        assertEquals(CardOpen(key), late.state)
        assertEquals(listOf(Haptic, OpenCard(key)), late.effects)
    }

    @Test fun dismissDuringOpeningClosesReversibly() {
        val r = run().send(down(), Tick(400), up(500), Dismiss(520))
        assertEquals(CardClosing(key), r.state)
        assertEquals(CloseCard, r.effects.last())
    }

    @Test fun aNewPressIsAdmittedWhileTheCardIsClosing() {
        val r = run(CardClosing(key)).send(down(t = 100))
        assertEquals(Pressing(key, origin, 100, false), r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test fun aNewPressIsAdmittedWhileSettlingAndRetargetsFromTheCurrentValue() {
        val r = run().send(down(), up(80), down(t = 120))
        assertEquals(Pressing(key, origin, 120, false), r.state)
        assertEquals(0f, reducer.holdTarget(r.state, 200), 0f)
        assertEquals(1f, reducer.holdTarget(r.state, 520), 0f)
    }

    // Disabled admissions

    @Test fun downWhileDisabledIsIgnored() {
        val r = run().send(down(admitted = false), Tick(500), up(600))
        assertEquals(Idle, r.state)
        assertTrue(r.effects.isEmpty())
    }

    @Test fun listDisabledMidPressCancels() {
        assertEquals(listOf(Settle), run().send(down(), Tick(200), Cancel(250)).effects)
    }

    @Test fun timingRejectsALiftInsideTheDeadZone() {
        assertThrows(IllegalArgumentException::class.java) { FocusTiming(100, 100, 8f) }
        assertThrows(IllegalArgumentException::class.java) { FocusTiming(-1, 400, 8f) }
        assertThrows(IllegalArgumentException::class.java) { FocusTiming(100, 400, 0f) }
    }

    // Exhaustive table: every state × every event kind, with the pointer inside slop and stamped before any lift.

    private enum class Kind { Down, DownDisabled, MoveNear, MoveFar, MoveTwo, Up, UpFar, Cancel, Tick, DragEnded, CardPresented, Dismiss, Settled }

    private fun event(kind: Kind, t: Long): FocusEvent = when (kind) {
        Kind.Down -> down(t = t)
        Kind.DownDisabled -> down(t = t, admitted = false)
        Kind.MoveNear -> move(t, dx = 3f)
        Kind.MoveFar -> move(t, dy = 30f)
        Kind.MoveTwo -> move(t, dx = 1f, pointers = 2)
        Kind.Up -> up(t)
        Kind.UpFar -> up(t, dy = 30f)
        Kind.Cancel -> Cancel(t)
        Kind.Tick -> Tick(t)
        Kind.DragEnded -> DragEnded(t)
        Kind.CardPresented -> CardPresented(t)
        Kind.Dismiss -> Dismiss(t)
        Kind.Settled -> Settled(t)
    }

    private val pressing = Pressing(key, origin, 0, false)
    private val pressingMutable = Pressing(key, origin, 0, true)
    private val lifted = Lifted(key, origin, 0, false, origin)
    private val liftedMutable = Lifted(key, origin, 0, true, origin)
    private val following = Following(key, origin, 0, origin + Offset(0f, 20f))

    /** (state, event kind) → expected next state and effects. Events are stamped at t = 200 (before the 400 ms lift). */
    private val table: List<Triple<FocusState, Kind, Pair<FocusState, List<FocusEffect>>>> = listOf(
        // Idle
        Triple(Idle, Kind.Down, Pressing(key, origin, 200, false) to emptyList()),
        Triple(Idle, Kind.DownDisabled, Idle to emptyList()),
        Triple(Idle, Kind.MoveNear, Idle to emptyList()), Triple(Idle, Kind.MoveFar, Idle to emptyList()), Triple(Idle, Kind.MoveTwo, Idle to emptyList()),
        Triple(Idle, Kind.Up, Idle to emptyList()), Triple(Idle, Kind.UpFar, Idle to emptyList()), Triple(Idle, Kind.Cancel, Idle to emptyList()),
        Triple(Idle, Kind.Tick, Idle to emptyList()), Triple(Idle, Kind.DragEnded, Idle to emptyList()), Triple(Idle, Kind.CardPresented, Idle to emptyList()),
        Triple(Idle, Kind.Dismiss, Idle to emptyList()), Triple(Idle, Kind.Settled, Idle to emptyList()),
        // Settling
        Triple(Settling, Kind.Down, Pressing(key, origin, 200, false) to emptyList()),
        Triple(Settling, Kind.DownDisabled, Settling to emptyList()),
        Triple(Settling, Kind.MoveNear, Settling to emptyList()), Triple(Settling, Kind.MoveFar, Settling to emptyList()), Triple(Settling, Kind.MoveTwo, Settling to emptyList()),
        Triple(Settling, Kind.Up, Settling to emptyList()), Triple(Settling, Kind.UpFar, Settling to emptyList()), Triple(Settling, Kind.Cancel, Settling to emptyList()),
        Triple(Settling, Kind.Tick, Settling to emptyList()), Triple(Settling, Kind.DragEnded, Settling to emptyList()), Triple(Settling, Kind.CardPresented, Settling to emptyList()),
        Triple(Settling, Kind.Dismiss, Settling to emptyList()), Triple(Settling, Kind.Settled, Idle to emptyList()),
        // Pressing (immutable)
        Triple(pressing, Kind.Down, Settling to listOf(Settle)),
        Triple(pressing, Kind.DownDisabled, Settling to listOf(Settle)),
        Triple(pressing, Kind.MoveNear, pressing to emptyList()),
        Triple(pressing, Kind.MoveFar, Settling to listOf(YieldScroll, Settle)),
        Triple(pressing, Kind.MoveTwo, Settling to listOf(Settle)),
        Triple(pressing, Kind.Up, Settling to listOf(Tap(key), Settle)),
        Triple(pressing, Kind.UpFar, Settling to listOf(Tap(key), Settle)),
        Triple(pressing, Kind.Cancel, Settling to listOf(Settle)),
        Triple(pressing, Kind.Tick, pressing to emptyList()),
        Triple(pressing, Kind.DragEnded, pressing to emptyList()), Triple(pressing, Kind.CardPresented, pressing to emptyList()),
        Triple(pressing, Kind.Dismiss, pressing to emptyList()), Triple(pressing, Kind.Settled, pressing to emptyList()),
        // Pressing (mutable) behaves identically before the lift
        Triple(pressingMutable, Kind.MoveFar, Settling to listOf(YieldScroll, Settle)),
        Triple(pressingMutable, Kind.Up, Settling to listOf(Tap(key), Settle)),
        Triple(pressingMutable, Kind.MoveNear, pressingMutable to emptyList()),
        // Lifted (immutable)
        Triple(lifted, Kind.Down, lifted to emptyList()),
        Triple(lifted, Kind.DownDisabled, lifted to emptyList()),
        Triple(lifted, Kind.MoveNear, lifted.copy(position = origin + Offset(3f, 0f)) to emptyList()),
        Triple(lifted, Kind.MoveFar, following.copy(position = origin + Offset(0f, 30f)) to emptyList()),
        Triple(lifted, Kind.MoveTwo, lifted.copy(position = origin + Offset(1f, 0f)) to emptyList()),
        Triple(lifted, Kind.Up, CardOpening(key) to listOf(OpenCard(key))),
        Triple(lifted, Kind.UpFar, Settling to listOf(Settle)),
        Triple(lifted, Kind.Cancel, Settling to listOf(Settle)),
        Triple(lifted, Kind.Tick, lifted to emptyList()),
        Triple(lifted, Kind.DragEnded, lifted to emptyList()), Triple(lifted, Kind.CardPresented, lifted to emptyList()),
        Triple(lifted, Kind.Dismiss, lifted to emptyList()), Triple(lifted, Kind.Settled, lifted to emptyList()),
        // Lifted (mutable)
        Triple(liftedMutable, Kind.MoveFar, Dragging(key) to listOf(BeginDrag(key, origin))),
        Triple(liftedMutable, Kind.MoveNear, liftedMutable.copy(position = origin + Offset(3f, 0f)) to emptyList()),
        Triple(liftedMutable, Kind.Up, CardOpening(key) to listOf(OpenCard(key))),
        Triple(liftedMutable, Kind.UpFar, Settling to listOf(Settle)),
        // Following
        Triple(following, Kind.Down, following to emptyList()),
        Triple(following, Kind.DownDisabled, following to emptyList()),
        Triple(following, Kind.MoveNear, following.copy(position = origin + Offset(3f, 0f)) to emptyList()),
        Triple(following, Kind.MoveFar, following.copy(position = origin + Offset(0f, 30f)) to emptyList()),
        Triple(following, Kind.MoveTwo, following.copy(position = origin + Offset(1f, 0f)) to emptyList()),
        Triple(following, Kind.Up, Settling to listOf(Settle)),
        Triple(following, Kind.UpFar, Settling to listOf(Settle)),
        Triple(following, Kind.Cancel, Settling to listOf(Settle)),
        Triple(following, Kind.Tick, following to emptyList()),
        Triple(following, Kind.DragEnded, following to emptyList()), Triple(following, Kind.CardPresented, following to emptyList()),
        Triple(following, Kind.Dismiss, following to emptyList()), Triple(following, Kind.Settled, following to emptyList()),
        // Dragging
        Triple(Dragging(key), Kind.Down, Dragging(key) to emptyList()),
        Triple(Dragging(key), Kind.DownDisabled, Dragging(key) to emptyList()),
        Triple(Dragging(key), Kind.MoveNear, Dragging(key) to emptyList()), Triple(Dragging(key), Kind.MoveFar, Dragging(key) to emptyList()), Triple(Dragging(key), Kind.MoveTwo, Dragging(key) to emptyList()),
        Triple(Dragging(key), Kind.Up, Settling to listOf(Settle)), Triple(Dragging(key), Kind.UpFar, Settling to listOf(Settle)),
        Triple(Dragging(key), Kind.Cancel, Settling to listOf(Settle)),
        Triple(Dragging(key), Kind.Tick, Dragging(key) to emptyList()),
        Triple(Dragging(key), Kind.DragEnded, Settling to listOf(Settle)),
        Triple(Dragging(key), Kind.CardPresented, Dragging(key) to emptyList()), Triple(Dragging(key), Kind.Dismiss, Dragging(key) to emptyList()), Triple(Dragging(key), Kind.Settled, Dragging(key) to emptyList()),
        // CardOpening
        Triple(CardOpening(key), Kind.Down, Settling to listOf(CloseCard, Settle)),
        Triple(CardOpening(key), Kind.DownDisabled, Settling to listOf(CloseCard, Settle)),
        Triple(CardOpening(key), Kind.MoveNear, CardOpening(key) to emptyList()), Triple(CardOpening(key), Kind.MoveFar, CardOpening(key) to emptyList()), Triple(CardOpening(key), Kind.MoveTwo, CardOpening(key) to emptyList()),
        Triple(CardOpening(key), Kind.Up, CardOpening(key) to emptyList()), Triple(CardOpening(key), Kind.UpFar, CardOpening(key) to emptyList()), Triple(CardOpening(key), Kind.Cancel, CardOpening(key) to emptyList()),
        Triple(CardOpening(key), Kind.Tick, CardOpening(key) to emptyList()), Triple(CardOpening(key), Kind.DragEnded, CardOpening(key) to emptyList()),
        Triple(CardOpening(key), Kind.CardPresented, CardOpen(key) to emptyList()),
        Triple(CardOpening(key), Kind.Dismiss, CardClosing(key) to listOf(CloseCard)),
        Triple(CardOpening(key), Kind.Settled, CardOpening(key) to emptyList()),
        // CardOpen
        Triple(CardOpen(key), Kind.Down, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.DownDisabled, CardOpen(key) to emptyList()),
        Triple(CardOpen(key), Kind.MoveNear, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.MoveFar, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.MoveTwo, CardOpen(key) to emptyList()),
        Triple(CardOpen(key), Kind.Up, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.UpFar, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.Cancel, CardOpen(key) to emptyList()),
        Triple(CardOpen(key), Kind.Tick, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.DragEnded, CardOpen(key) to emptyList()), Triple(CardOpen(key), Kind.CardPresented, CardOpen(key) to emptyList()),
        Triple(CardOpen(key), Kind.Dismiss, CardClosing(key) to listOf(CloseCard)),
        Triple(CardOpen(key), Kind.Settled, CardOpen(key) to emptyList()),
        // CardClosing
        Triple(CardClosing(key), Kind.Down, Pressing(key, origin, 200, false) to emptyList()),
        Triple(CardClosing(key), Kind.DownDisabled, CardClosing(key) to emptyList()),
        Triple(CardClosing(key), Kind.MoveNear, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.MoveFar, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.MoveTwo, CardClosing(key) to emptyList()),
        Triple(CardClosing(key), Kind.Up, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.UpFar, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.Cancel, CardClosing(key) to emptyList()),
        Triple(CardClosing(key), Kind.Tick, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.DragEnded, CardClosing(key) to emptyList()), Triple(CardClosing(key), Kind.CardPresented, CardClosing(key) to emptyList()),
        Triple(CardClosing(key), Kind.Dismiss, CardClosing(key) to emptyList()),
        Triple(CardClosing(key), Kind.Settled, Idle to emptyList()),
    )

    @Test fun transitionTableIsExhaustiveAndMatches() {
        val states = listOf(Idle, Settling, pressing, lifted, following, Dragging(key), CardOpening(key), CardOpen(key), CardClosing(key))
        for (state in states) for (kind in Kind.entries) {
            assertTrue("table covers $state × $kind", table.any { it.first == state && it.second == kind })
        }
        for ((state, kind, expected) in table) {
            val effects = mutableListOf<FocusEffect>()
            val next = reducer.reduce(state, event(kind, 200), effects)
            assertEquals("$state × $kind → state", expected.first, next)
            assertEquals("$state × $kind → effects", expected.second, effects)
        }
    }

    @Test fun everyPressStateLiftsFirstWhenTheEventIsStampedAtTheLiftPoint() {
        for (kind in Kind.entries) {
            val effects = mutableListOf<FocusEffect>()
            val next = reducer.reduce(pressing, event(kind, 400), effects)
            assertEquals("$kind lifts first", Haptic, effects.firstOrNull())
            // After the lift the event applies with the Lifted rules.
            val liftedEffects = mutableListOf<FocusEffect>()
            val fromLifted = reducer.reduce(lifted, event(kind, 400), liftedEffects)
            assertEquals("$kind after lift", fromLifted, next)
            assertEquals("$kind after lift effects", liftedEffects, effects.drop(1))
        }
    }

    @Test fun pointerIsNeverOwnedBeforeTheLift() {
        for (state in listOf(Idle, Settling, pressing, CardOpening(key), CardOpen(key), CardClosing(key))) assertFalse("$state", state.ownsPointer)
        for (state in listOf(lifted, following, Dragging(key))) assertTrue("$state", state.ownsPointer)
    }
}
