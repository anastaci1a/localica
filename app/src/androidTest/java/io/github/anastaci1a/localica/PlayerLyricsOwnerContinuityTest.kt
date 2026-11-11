package io.github.anastaci1a.localica

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test

/** #600 U10.03: the lyrics layout is one expand/collapse owner on the #608 law over the title's travel (lead decision
 * 2026-11-07 12:10 PM (4)). The presented title follows the law from the title tap, and Back reversing the opening
 * rejoins it C1; the retired 412 ms tween deviates from the law and records no owner join. */
@OptIn(ExperimentalTestApi::class)
class PlayerLyricsOwnerContinuityTest {
    private class Scene { var clock: UiClock? = null; val recorder = JoinRecorder("player-lyrics"); var closed = 0.0; var travel = 1.0
        fun objectTime() = LyricsLaw.objectTime(kotlin.math.abs(travel), clock!!.context!!.fullPlayerReferenceTravelPx.toDouble()) }

    private fun ComposeUiTest.title() = onNodeWithTag("player-title", true).fetchSemanticsNode().boundsInRoot.top.toDouble()
    private fun ComposeUiTest.phase(scene: Scene) = (title() - scene.closed) / scene.travel
    private fun ComposeUiTest.now() = mainClock.currentTime * 1_000_000
    private fun ComposeUiTest.frames(scene: Scene, count: Int, into: MutableList<PhaseFrame>) =
        repeat(count) { mainClock.advanceTimeByFrame(); into += PhaseFrame(now(), phase(scene)) }
    private fun ComposeUiTest.openFromTitle() = onNodeWithTag("player-lyrics-title-target", true).performSemanticsAction(SemanticsActions.OnClick)
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    @Test fun titleTapThenBackReversalFollowTheLawWithC1Joins() = localicaRunComposeUiTest {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("interface", 0).edit().clear().commit()
        val scene = Scene(); val tracks = (1..2).map { Track("lyrics-$it", "Evening $it", fileName = "song$it.mp3", relativePath = "song$it.mp3") }
        setContent {
            val clock = remember { UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it }
                override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame) }, { mainClock.currentTime * 1_000_000 }) }
            SideEffect { if (scene.clock == null) { clock.events = scene.recorder; scene.clock = clock } }
            LaunchedEffect(clock) { clock.run() }
            CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock) { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true, trackId = tracks[0].id, trackUri = tracks[0].uri, title = tracks[0].title),
                {}, {}, {}, {}, {}, {}) } }
        }
        waitForIdle(); mainClock.autoAdvance = false
        onNodeWithTag("player-surface").performClick(); repeat(60) { mainClock.advanceTimeByFrame() }
        // Measure both title seats from layout: ordinary, then lyrics open, then closed again.
        scene.closed = title()
        openFromTitle(); repeat(60) { mainClock.advanceTimeByFrame() }
        scene.travel = title() - scene.closed
        assertTrue("The title travels when lyrics open", kotlin.math.abs(scene.travel) > 8.0)
        back(); repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("closed seat is stable", scene.closed, title(), 1.0)
        scene.recorder.joins.clear()
        val frames = mutableListOf<PhaseFrame>(); val t0 = now()
        openFromTitle(); frames(scene, 6, frames)
        assertTrue("The opening is mid-flight when Back reverses it", frames.last().p in .05..0.95)
        back(); frames(scene, 45, frames)
        assertTrue("The lyrics layout has an Engine owner: joins ${scene.recorder.joins.size}", scene.recorder.joins.size >= 2)
        verifyReleaseLaw(LyricsLaw, "title tap/Back", frames, scene.recorder.joins, fallbackJoin(t0, 0.0, 0.0, true), scene.objectTime())
        assertEquals("Back closes the lyrics layout", 0.0, frames.last().p, 1e-3)
    }
}
