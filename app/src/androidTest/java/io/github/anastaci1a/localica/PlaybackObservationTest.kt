package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackObservationTest {
    @get:Rule val compose = createComposeRule()
    private var roots = 0
    private var samples = 0
    private var rootValue: PlaybackState? = null
    private var sampleValue: PlaybackState? = null
    private var currentSample: (() -> PlaybackState)? = null

    @Composable private fun SampleOwner(sample: () -> PlaybackState) {
        val value = sample()
        SideEffect { samples++; sampleValue = value }
    }
    @Composable private fun ScreenOwner(observation: PlaybackObservation) {
        val screen = observation.screen.value
        SideEffect { roots++; rootValue = screen; currentSample = observation.sample }
        SampleOwner(observation.sample)
    }

    @Test fun positionSamplesUpdateChildAndEventReadsWithoutRecomposingScreenOwner() {
        val source = mutableStateOf(PlaybackState(connected = true, trackId = "song", trackUri = "song-uri",
            durationMs = 60000, positionMs = 1000, sampledAtMs = 2000, isPlaying = true))
        compose.setContent { ScreenOwner(rememberPlaybackObservation(source)) }
        compose.waitForIdle()
        val initialRoots = roots
        repeat(8) { index ->
            val next = source.value.copy(positionMs = 2000L + index * 250, sampledAtMs = 3000L + index * 250)
            val beforeSamples = samples
            compose.runOnIdle {
                source.value = next
                // Event-time reads see the latest complete sample even before the child recomposes.
                assertSame(next, requireNotNull(currentSample).invoke())
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(initialRoots, roots)
                assertTrue(samples > beforeSamples)
                assertSame(next, sampleValue)
                assertEquals(next.copy(positionMs = 0, sampledAtMs = 0), rootValue)
            }
        }
        val changed = source.value.copy(isPlaying = false, playWhenReady = false,
            seekAcknowledgement = SeekAcknowledgement(7, 0, "visit", false), playbackVisit = "visit")
        compose.runOnIdle { source.value = changed }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(roots > initialRoots)
            assertEquals(changed.copy(positionMs = 0, sampledAtMs = 0), rootValue)
            assertSame(changed, sampleValue)
        }
    }

    @Test fun replacingSampleOwnerDetachesOldSourceAndKeepsAllNewMetadata() {
        val old = mutableStateOf(PlaybackState(trackId = "old", positionMs = 111, sampledAtMs = 222))
        val fresh = mutableStateOf(PlaybackState(trackId = "new", title = "New", positionMs = 333,
            sampledAtMs = 444, problem = PlaybackProblem.Unreadable, ended = true))
        val selected = mutableStateOf<State<PlaybackState>>(old, referentialEqualityPolicy())
        compose.setContent { ScreenOwner(rememberPlaybackObservation(selected.value)) }
        compose.waitForIdle()
        compose.runOnIdle { selected.value = fresh }
        compose.waitForIdle()
        val afterRoots = roots
        val afterSamples = samples
        compose.runOnIdle {
            assertSame(fresh.value, requireNotNull(currentSample).invoke())
            assertSame(fresh.value, sampleValue)
            assertEquals(fresh.value.copy(positionMs = 0, sampledAtMs = 0), rootValue)
            old.value = old.value.copy(trackId = "obsolete", positionMs = 555, sampledAtMs = 666)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(afterRoots, roots)
            assertEquals(afterSamples, samples)
            assertSame(fresh.value, sampleValue)
        }
    }

    @Test fun actualPlayerSeekUsesLiveSamplesInsteadOfNormalizedScreenPosition() {
        val source = mutableStateOf(PlaybackState(connected = true, title = "Sample owner",
            trackId = "song", durationMs = 60000, positionMs = 10000, sampledAtMs = 2000,
            playbackVisit = "visit"))
        compose.setContent {
            val observation = rememberPlaybackObservation(source)
            LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(observation.screen.value, true, {}, InterfaceSettings(reducedMotion = true),
                    remember { Animatable(1f) }, {}, {}, {}, {}, {}, {}, sampleState = observation.sample)
            } }
        }
        fun value() = compose.onNodeWithContentDescription("Seek").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.waitForIdle()
        assertEquals(10000f, value(), 1f)
        compose.runOnIdle { source.value = source.value.copy(positionMs = 20000, sampledAtMs = 3000) }
        compose.waitForIdle()
        assertEquals(20000f, value(), 1f)
        compose.runOnIdle {
            source.value = source.value.copy(positionMs = 5000, sampledAtMs = 4000,
                seekAcknowledgement = SeekAcknowledgement(9, 5000, "visit"))
        }
        compose.waitForIdle()
        assertEquals(5000f, value(), 1f)
    }
}
