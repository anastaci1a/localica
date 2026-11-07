package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.engine.FocusEffect.*
import io.github.anastaci1a.localica.ui.engine.FocusEvent.*
import io.github.anastaci1a.localica.ui.engine.FocusState.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** #640 §4.4: 10⁴ random timed sequences per list kind; every gesture has exactly one outcome and the lift rules hold. */
class FocusReducerFuzzTest {
    private val timing = FocusTiming(deadZoneMs = 100, liftMs = 400, slopPx = 8f)
    private val reducer = FocusReducer(timing)

    private class Gesture { var tapped = 0; var yielded = 0; var dragged = 0; var carded = 0; var followed = false; var haptics = 0; var settles = 0; var movedBeyondSlop = false; var ended = false }

    @Test fun tenThousandSequencesKeepTheInvariants() {
        val random = Random(640)
        var lifted = 0; var cards = 0; var taps = 0; var yields = 0; var drags = 0; var follows = 0; var cancels = 0
        repeat(10_000) { i ->
            val mutable = random.nextBoolean()
            val down = Offset(random.nextInt(0, 400).toFloat(), random.nextInt(0, 800).toFloat())
            var state: FocusState = if (random.nextInt(8) == 0) Settling else Idle
            val effects = mutableListOf<FocusEffect>()
            var t = random.nextLong(0, 10_000)
            val t0 = t
            state = reducer.reduce(state, Down(t, "item", down, mutable), effects)
            assertTrue("seq $i admitted down", state is Pressing)
            val g = Gesture()
            var position = down
            var pressed = true
            var events = 0
            while (state != Idle && events < 60) {
                t += random.nextLong(1, 140)
                val event: FocusEvent = when (random.nextInt(14)) {
                    0, 1, 2, 3 -> if (pressed) { position = jitter(random, position, down); Move(t, position, if (random.nextInt(12) == 0) 2 else 1) } else Tick(t)
                    4 -> if (pressed) { pressed = false; Up(t, position) } else Tick(t)
                    5 -> Tick(t)
                    6 -> if (pressed && random.nextInt(6) == 0) { pressed = false; Cancel(t) } else Tick(t)
                    7 -> DragEnded(t)
                    8 -> CardPresented(t)
                    9 -> Dismiss(t)
                    10 -> Settled(t)
                    11 -> Down(t, "other", position, mutable)
                    else -> Tick(t)
                }
                val before = state
                val sizeBefore = effects.size
                state = reducer.reduce(state, event, effects)
                events++
                if (event is Move && (event.position - down).getDistance() > timing.slopPx) g.movedBeyondSlop = true
                if (event is Up && (event.position - down).getDistance() > timing.slopPx) g.movedBeyondSlop = true
                if (state.ownsPointer) assertTrue("seq $i: owning the pointer before the lift (t=$t, t0=$t0)", t >= t0 + timing.liftMs)
                if (state is Following) g.followed = true
                for (e in effects.drop(sizeBefore)) when (e) {
                    Haptic -> { g.haptics++; assertTrue("seq $i: haptic before the lift point", t >= t0 + timing.liftMs); assertTrue("seq $i: haptic from a non-press state $before", before is Pressing) }
                    is Tap -> g.tapped++
                    YieldScroll -> g.yielded++
                    is BeginDrag -> { g.dragged++; assertTrue("seq $i: drag in an immutable list", mutable); assertEquals(down, e.down) }
                    is OpenCard -> { g.carded++; assertFalse("seq $i: card after the slop was exceeded", g.movedBeyondSlop); assertEquals(0, g.yielded) }
                    CloseCard -> {}
                    Settle -> g.settles++
                }
                // The second gesture (a Down after the first one ended) is out of scope: stop at the first terminal state.
                if (state == Settling || state is Card) { g.ended = true; break }
                if (state is Pressing && before is Pressing && (state as Pressing).t0 != t0) break
            }
            if (!g.ended) return@repeat
            val liftedGesture = g.haptics == 1
            assertTrue("seq $i: at most one haptic", g.haptics <= 1)
            if (state is Card || g.followed || g.dragged > 0) assertTrue("seq $i: card/follow/drag without lift", liftedGesture)
            val outcomes = g.tapped + g.yielded + g.dragged + g.carded + (if (g.followed) 1 else 0)
            val cancel = outcomes == 0 && state == Settling
            assertEquals("seq $i: exactly one outcome (tap=${g.tapped} yield=${g.yielded} drag=${g.dragged} card=${g.carded} follow=${g.followed} cancel=$cancel)", 1, outcomes + (if (cancel) 1 else 0))
            if (g.tapped > 0 || g.yielded > 0) assertFalse("seq $i: tap or yield after lift", liftedGesture)
            if (liftedGesture) lifted++
            if (g.carded > 0) cards++; if (g.tapped > 0) taps++; if (g.yielded > 0) yields++; if (g.dragged > 0) drags++; if (g.followed) follows++; if (cancel) cancels++
        }
        // The generator must exercise every outcome, or the invariants above are vacuous.
        assertTrue("lifted $lifted", lifted > 500); assertTrue("cards $cards", cards > 100); assertTrue("taps $taps", taps > 500)
        assertTrue("yields $yields", yields > 500); assertTrue("drags $drags", drags > 100); assertTrue("follows $follows", follows > 100); assertTrue("cancels $cancels", cancels > 100)
    }

    @Test fun replayIsDeterministic() {
        fun trace(seed: Int): List<Pair<FocusState, List<FocusEffect>>> {
            val random = Random(seed)
            var state: FocusState = Idle
            val out = mutableListOf<Pair<FocusState, List<FocusEffect>>>()
            var t = 0L
            repeat(200) {
                t += random.nextLong(1, 120)
                val p = Offset(random.nextInt(0, 30).toFloat(), random.nextInt(0, 30).toFloat())
                val event: FocusEvent = when (random.nextInt(9)) { 0 -> Down(t, "k", p, random.nextBoolean()); 1, 2 -> Move(t, p); 3 -> Up(t, p); 4 -> Cancel(t); 5 -> DragEnded(t); 6 -> CardPresented(t); 7 -> Dismiss(t); else -> Settled(t) }
                val effects = mutableListOf<FocusEffect>()
                state = reducer.reduce(state, event, effects)
                out += state to effects
            }
            return out
        }
        assertEquals(trace(7), trace(7))
        assertNotEquals(trace(7), trace(8))
    }

    private fun jitter(random: Random, position: Offset, down: Offset): Offset = when (random.nextInt(5)) {
        0 -> down + Offset(random.nextFloat() * 6f - 3f, random.nextFloat() * 6f - 3f) // inside slop
        1 -> position + Offset(0f, random.nextFloat() * 60f)                            // scrolling away
        2 -> down                                                                       // back on the down point
        else -> position + Offset(random.nextFloat() * 4f - 2f, random.nextFloat() * 4f - 2f)
    }
}
