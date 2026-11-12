package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #854 review F2 (#862): with "Respect system animation scale" off, the window's one WindowMotionScale gives every consumer 1.0x at
 * any platform scale: the engine (the root's durationScale), Compose animations and each coroutine reader. On, they follow it. */
class WindowMotionScaleTest {
    private val platforms = listOf(.5f, 0f, 2f)
    private val geometry = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 2000f, 1f / 60)) as SpecResult.Accepted).spec
    private class Fixed(override val scaleFactor: Float) : MotionDurationScale

    @Test fun theEffectiveScaleIsThePlatformOnlyWhileRespected() {
        for (platform in platforms + 1f) {
            assertEquals(1f, WindowMotionScale(platform, respect = false).scaleFactor, 0f)
            assertEquals(platform, WindowMotionScale(platform, respect = true).scaleFactor, 0f)
            assertEquals(1f, effectiveDurationScale(platform, false), 0f)
            assertFalse("an ignored 0 is not Reduce motion", effectiveReducedMotion(false, platform, respectSystemScale = false))
        }
        val scale = WindowMotionScale(2f, respect = true)
        scale.respect = false; assertEquals(1f, scale.scaleFactor, 0f)
        scale.platform = 0f; scale.respect = true; assertEquals(0f, scale.scaleFactor, 0f)
        assertEquals(0f, (Dispatchers.Unconfined + scale).motionDurationScale, 0f)
        assertEquals(0f, (Dispatchers.Unconfined + scale).platformDurationScale, 0f)
        scale.respect = false
        assertEquals(1f, (Dispatchers.Unconfined + scale).motionDurationScale, 0f)
        assertEquals("the root still sees the platform value", 0f, (Dispatchers.Unconfined + scale).platformDurationScale, 0f)
    }

    /** A 60 Hz frame clock; a Compose animation reads MotionDurationScale from the same coroutine context. */
    private class Frames : MonotonicFrameClock {
        var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { nanos += 16_666_667L; return onFrame(nanos) }
    }
    private fun composeNanos(scale: MotionDurationScale): Long = runBlocking {
        val frames = Frames()
        withContext(frames + scale) { Animatable(0f).animateTo(1f, tween(200, easing = LinearEasing)); frames.nanos }
    }

    @Test fun aComposeAnimationRunsAtOneXWhenThePlatformScaleIsIgnored() {
        val normal = composeNanos(Fixed(1f))
        for (platform in platforms) {
            assertEquals("ignored $platform", normal, composeNanos(WindowMotionScale(platform, respect = false)))
            assertEquals("respected $platform", composeNanos(Fixed(platform)), composeNanos(WindowMotionScale(platform, respect = true)))
        }
        assertTrue(composeNanos(Fixed(2f)) > normal && composeNanos(Fixed(0f)) < normal)
    }

    /** Former direct reader: the pinch owner reads the coroutine element, which is the window's effective scale. */
    private fun pinchFrames(scale: MotionDurationScale): List<Int> {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = UiClock(object : FrameSource {
            override suspend fun awaitFrame() = frames.receive()
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }, { Long.MIN_VALUE / 4 }, {})
        val owner = BrowsePinchProgressOwner().also { it.bind(clock, Dispatchers.Unconfined) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { clock.run() }
        val values = mutableListOf<Int>()
        try {
            val caller = scope.launch(scale, start = CoroutineStart.UNDISPATCHED) { owner.animate(0f, 1f, tween(200)) { values += it.toRawBits() } }
            var nanos = 1_000_000_000L
            while (caller.isActive && values.size < 1000) { check(frames.trySend(nanos).isSuccess); nanos += 16_666_667L }
            assertFalse(caller.isActive)
        } finally { scope.cancel(); frames.close() }
        return values
    }

    @Test fun thePinchReaderRunsAtOneXWhenThePlatformScaleIsIgnored() {
        val normal = pinchFrames(Fixed(1f))
        for (platform in platforms) {
            assertEquals("ignored $platform", normal, pinchFrames(WindowMotionScale(platform, respect = false)))
            assertEquals("respected $platform", pinchFrames(Fixed(platform)), pinchFrames(WindowMotionScale(platform, respect = true)))
        }
    }

    /** The engine: the root publishes effectiveDurationScale(platform, respect) as EngineContext.durationScale. */
    private fun engineOpenSeconds(durationScale: Float): Double = EngineHarness(spec = geometry).use { h ->
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, durationScale, 1f), geometry)
        val popup = PopupWindowMotion(h.clock, geometry.expansion, "scale", false, false, PopupKind.Menu)
        popup.show(true); popup.place(Rect(0f, 0f, 48f, 48f), Rect(20f, 30f, 400f, 600f), 1f)
        popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration)
        var seconds = 0.0
        while (!(popup.expansion.expanded && popup.expansion.phase.settled) && seconds < 20) { h.advance(1.0 / 120); seconds += 1.0 / 120 }
        popup.dispose()
        seconds
    }

    @Test fun anEnginePopupRunsAtOneXWhenThePlatformScaleIsIgnored() {
        val normal = engineOpenSeconds(1f)
        for (platform in platforms) assertEquals("ignored $platform", normal, engineOpenSeconds(effectiveDurationScale(platform, respect = false)), 0.0)
        assertTrue("respected 2x is slower", engineOpenSeconds(effectiveDurationScale(2f, respect = true)) > normal * 1.5)
    }

    /** Source contract: the window scale is the app's only MotionDurationScale; every other mention reads the coroutine element;
     * MainActivity installs it before its content, and the root binds the preference to it. */
    @Test fun theWindowScaleIsTheOnlyProviderAndEveryReaderUsesTheCoroutineElement() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val implementations = sources.filter { Regex("""(object|class)[^\n{]*:[^\n{]*\bMotionDurationScale\b""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("InterfaceMotion.kt"), implementations)
        val readers = mutableSetOf<String>()
        for (file in sources) if (file.name != "InterfaceMotion.kt") for (line in file.readLines()) {
            val code = line.trim()
            if ("MotionDurationScale" !in code || code.startsWith("import ") || code.startsWith("//") || code.startsWith("*")) continue
            assertTrue("${file.name}: $code", "[MotionDurationScale]" in code)
            readers += file.name
        }
        assertEquals(setOf("ArtistFilterRange.kt", "SettingsSearchScroll.kt", "BrowsePinchProgressMotion.kt"), readers)
        val main = File("src/main/java/io/github/anastaci1a/localica/MainActivity.kt").readText()
        assertTrue(main.indexOf("installLocalicaWindowMotion(") in 0 until main.indexOf("setContent {"))
        assertTrue(File("src/main/java/io/github/anastaci1a/localica/ui/LocalicaUiRoot.kt").readText().contains("bindRespectSystemAnimationScale(respect)"))
    }
}
