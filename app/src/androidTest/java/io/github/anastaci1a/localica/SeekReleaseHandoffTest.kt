package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.anastaci1a.localica.playback.PlaybackProblem
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playback.SeekAcknowledgement
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

class SeekReleaseHandoffTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private var capturedFrame = 0
    private var playback by mutableStateOf(PlaybackState(connected = true, trackUri = "song:a", trackId = "a",
        playbackVisit = "visit-a", positionMs = 20_000, durationMs = 120_000, sampledAtMs = 100,
        seekAcknowledgement = SeekAcknowledgement(4, 20_000, "visit-a")))
    private val seeks = mutableListOf<Long>()
    private var synchronousAck = false
    private var nextRequest = 4L
    private var rowVisible by mutableStateOf(true)
    private var cancelBounds by mutableStateOf<Rect?>(null)

    private fun fixture() {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
                    val position = rememberPlayerSeekPosition(playback)
                    if (rowVisible) PlayerSeekControls(playback, {}, cancelBounds = cancelBounds, position = position, onSeekRequest = { target, visit ->
                        assertEquals(playback.playbackVisit, visit)
                        seeks += target
                        val request = ++nextRequest
                        if (synchronousAck) acknowledge(target, request)
                        request
                    })
                }
            }
        } }
        compose.mainClock.autoAdvance = false
    }
    private fun seek() = compose.onNodeWithContentDescription("Seek")
    private fun value() = seek().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun acknowledge(target: Long, request: Long = 5, applied: Boolean = true) {
        playback = playback.copy(positionMs = target, sampledAtMs = playback.sampledAtMs + 1,
            seekAcknowledgement = SeekAcknowledgement(request, target, playback.playbackVisit, applied))
    }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun assertFrame(target: Long, label: String) {
        frame()
        assertEquals("$label: accessible position", target.toFloat(), value(), 1f)
        compose.onNodeWithText(formatTime(target)).assertExists()
        // Read the actual painted track, including release frames. The three candidate
        // positions occupy distinct pixels; semantics alone would miss a stale Canvas.
        val image = seek().captureToImage()
        val pixels = image.toPixelMap()
        val y = pixels.height / 2
        val track = (0 until pixels.width).filter { pixels[it, y].red > .12f }
        val fill = track.filter { pixels[it, y].red > .5f }
        assertTrue("$label: rendered track exists", track.isNotEmpty() && fill.isNotEmpty())
        val fraction = (fill.last() - track.first()).toFloat() / (track.last() - track.first())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(root, "seek-release-handoff/${testName.methodName}-api${Build.VERSION.SDK_INT}")
        check(output.isDirectory || output.mkdirs())
        val number = ++capturedFrame
        File(output, "frames.txt").appendText("$number $label expected=$target semantic=${value()} paintedFraction=$fraction live=${playback.positionMs} receipt=${playback.seekAcknowledgement}\n")
        File(output, "frame-${number.toString().padStart(3, '0')}.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals("$label: painted position", target.toFloat() / playback.durationMs!!, fraction, .035f)
    }

    @Test fun everyReleaseFrameRetainsFinalAgainstLiveStartAndNearTargetStaleSamples() {
        fixture()
        seek().performTouchInput { down(Offset(width * .4f, centerY)); moveTo(Offset(width * .8f, centerY)) }
        frame()
        val final = value().toLong()
        assertTrue("Final is distinct from 20s live and roughly 48s finger start", final > 85_000)
        seek().performTouchInput { up() }
        assertFrame(final, "first release frame")
        compose.runOnIdle { assertEquals(listOf(final), seeks) }
        for ((index, stale) in listOf(20_250L, 48_000L, final - 1, final + 1).withIndex()) {
            compose.runOnIdle { playback = playback.copy(positionMs = stale, sampledAtMs = 200L + index) }
            repeat(3) { assertFrame(final, "stale $stale frame $it") }
        }
        compose.runOnIdle { acknowledge(final) }
        assertFrame(final, "matching receipt")
        compose.runOnIdle { playback = playback.copy(positionMs = final + 1_000) }
        assertFrame(final + 1_000, "live playback resumes")
    }

    @Test fun finalMoveAndReleaseInOneInputBatchNeverReplayTheFingerDownPosition() {
        fixture()
        seek().performTouchInput {
            down(Offset(width * .4f, centerY))
            moveTo(Offset(width * .75f, centerY), delayMillis = 200)
            up()
        }
        frame()
        val final = seeks.single()
        assertTrue(final > 80_000)
        repeat(8) { assertFrame(final, "batched release $it") }
    }

    @Test fun rapidOppositeSeeksRejectEarlierReceiptAndAcceptCurrentOneImmediately() {
        fixture()
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "first seek")
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(10_000f)) }
        compose.runOnIdle { acknowledge(95_000, 5) }
        assertFrame(10_000, "older opposite receipt")
        compose.runOnIdle { acknowledge(10_000, 6) }
        assertFrame(10_000, "latest receipt")
        compose.runOnIdle { playback = playback.copy(positionMs = 11_000) }
        assertFrame(11_000, "no happy-path watchdog delay")
    }


    @Test fun newerExternalSeekReceiptSupersedesSliderWhenEarlierReceiptWasConflated() {
        fixture()
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "slider request pending")
        compose.runOnIdle { acknowledge(30_000, 6) }
        assertFrame(30_000, "newer lyric or action seek supersedes pending slider")
        compose.runOnIdle { playback = playback.copy(positionMs = 31_000) }
        assertFrame(31_000, "newer external playback continues")
    }

    @Test fun abaDestinationSequenceDoesNotLetFirstReceiptReleaseTheThirdSeek() {
        fixture()
        for (target in listOf(95_000f, 10_000f, 95_000f)) {
            seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(target)) }
            frame()
        }
        compose.runOnIdle { acknowledge(95_000, 5) }
        assertFrame(95_000, "ABA first receipt still pending")
        compose.runOnIdle { acknowledge(10_000, 6) }
        assertFrame(95_000, "ABA middle receipt cannot overwrite latest target")
        compose.runOnIdle { acknowledge(94_500, 7) }
        assertFrame(94_500, "matching request accepts adjusted destination")
        compose.runOnIdle { playback = playback.copy(positionMs = 94_800) }
        assertFrame(94_800, "accepted playback advances")
    }

    @Test fun cancelledSecondDragKeepsFirstPendingSeekAndExplicitRejectionRestoresLive() {
        fixture()
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "first pending seek")
        seek().performTouchInput { down(center); moveTo(Offset(width * .3f, centerY)); cancel() }
        assertFrame(95_000, "second drag cancel preserves first commitment")
        compose.runOnIdle { assertEquals(listOf(95_000L), seeks); acknowledge(20_000, 5, applied = false) }
        assertFrame(20_000, "explicit refusal restores live immediately")
    }

    @Test fun synchronousAcknowledgementAndRefusedSeekWatchdogBothReleaseOwnership() {
        fixture()
        synchronousAck = true
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "synchronous receipt")
        compose.runOnIdle { playback = playback.copy(positionMs = 96_000); synchronousAck = false }
        assertFrame(96_000, "synchronous playback continues")
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(40_000f)) }
        assertFrame(40_000, "refused request retains preview initially")
        compose.mainClock.advanceTimeBy(SeekAcknowledgementTimeoutMs + 32)
        assertFrame(96_000, "refused request watchdog restores authority")
    }

    @Test fun cancellationNeverSeeksAndNextContactHasNoStalePendingPosition() {
        fixture()
        val bounds = seek().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { cancelBounds = Rect(bounds.left, bounds.bottom + 30, bounds.right, bounds.bottom + 130) }
        frame()
        seek().performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)); cancel() }
        assertFrame(20_000, "pointer cancellation")
        seek().performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
        seek().performTouchInput { moveTo(cancelBounds!!.center - bounds.topLeft); up() }
        assertFrame(20_000, "cancel target release")
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "new accessible contact")
        compose.runOnIdle { assertEquals(listOf(95_000L), seeks) }
    }

    @Test fun nativeVisitDurationDisconnectAndFailureInvalidatePendingOwnership() {
        fixture()
        val original = playback
        val changes = listOf<(PlaybackState) -> PlaybackState>(
            { it.copy(playbackVisit = "visit-b") }, // Same file, different queue occurrence.
            { it.copy(trackUri = "song:b", trackId = "b") },
            { it.copy(durationMs = 110_000) },
            { it.copy(connected = false) },
            { it.copy(problem = PlaybackProblem.Failed) },
        )
        for ((index, change) in changes.withIndex()) {
            compose.runOnIdle { playback = original }
            frame()
            seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
            assertFrame(95_000, "pending before source change $index")
            compose.runOnIdle { playback = change(playback).copy(positionMs = 30_000) }
            assertFrame(30_000, "source change $index")
        }
    }

    @Test fun pendingPositionSurvivesSeekRowRemovalAndRemountBeforeAcknowledgement() {
        fixture()
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        assertFrame(95_000, "pending before collapse")
        compose.runOnIdle { rowVisible = false }
        repeat(6) { frame() }
        seek().assertDoesNotExist()
        compose.runOnIdle { rowVisible = true }
        assertFrame(95_000, "reversed expansion before receipt")
        compose.runOnIdle { rowVisible = false; acknowledge(95_000) }
        frame()
        compose.runOnIdle { rowVisible = true; playback = playback.copy(positionMs = 96_000) }
        assertFrame(96_000, "receipt while collapsed releases pending")
    }

    @Test fun realPlayerSurfaceRetainsPendingSeekAcrossCollapseAndReversal() {
        lateinit var scope: CoroutineScope
        val expansion = Animatable(1f)
        compose.setContent { LocalicaTheme {
            scope = rememberCoroutineScope()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(playback, true, {}, InterfaceSettings(), expansion,
                    {}, {}, {}, {}, {}, {}, visual = PlayerVisual(), onSeekRequest = { target, visit ->
                        assertEquals(playback.playbackVisit, visit)
                        seeks += target
                        ++nextRequest
                    })
            }
        } }
        compose.mainClock.autoAdvance = false
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(95_000f)) }
        frame()
        compose.runOnIdle { scope.launch { expansion.snapTo(.5f) } }
        frame()
        seek().assertDoesNotExist()
        compose.runOnIdle { scope.launch { expansion.snapTo(1f) } }
        frame()
        assertEquals(95_000f, value(), 1f)
        compose.onNodeWithText("1:35").assertExists()
        compose.runOnIdle { acknowledge(95_000) }
        frame()
        compose.runOnIdle { playback = playback.copy(positionMs = 96_000) }
        frame()
        assertEquals(96_000f, value(), 1f)
    }

    @Test fun visitReplacementDuringHeldGestureCannotCommitOldMediaOnRelease() {
        fixture()
        seek().performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
        frame()
        compose.runOnIdle { playback = playback.copy(playbackVisit = "visit-b", positionMs = 30_000) }
        frame()
        seek().performTouchInput { up() }
        assertFrame(30_000, "new visit after old release")
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }
}
