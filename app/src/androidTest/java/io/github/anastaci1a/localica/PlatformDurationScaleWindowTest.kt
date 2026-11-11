package io.github.anastaci1a.localica

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The real window owns the system observer; only the first control substitutes frame delivery. */
class PlatformDurationScaleWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val published = AtomicReference<Owners?>()
    private val root = LocalicaUiRootState(InterfaceSettings())
    private var generation by mutableIntStateOf(0)
    private var pinchValue by mutableFloatStateOf(0f)
    private val contentBounds = AtomicReference<Rect?>()
    private val profile = AtomicReference<String?>()
    private data class Owners(val clock: UiClock, val engine: EngineContext, val systemScale: Float?,
        val scalar: MotionFloat, val spring: MotionFloat, val expansion: ExpandCollapse,
        val rect: MotionRect, val basis: ExpansionBasis, val scrollbar: ScrollbarMotion, val generation: Int,
        val pinch: BrowsePinchProgressOwner, val scope: CoroutineScope)

    @Composable private fun Scene() {
        val scope = rememberCoroutineScope()
        val systemScale = scope.coroutineContext[MotionDurationScale]?.scaleFactor
        LocalicaUiRoot(root) { LocalicaTheme {
            val engine = LocalEngineContext.current
            val clock = ownerClock()
            val pinch = remember(clock) { BrowsePinchProgressOwner() }
            BindBrowsePinchProgressClock(pinch)
            val scalar = rememberNormalizedMotionFloat(generation to "scalar", 0f)
            val spring = rememberNormalizedMotionFloat(generation to "spring", 0f)
            val expansion = rememberExpandCollapse(generation to "expansion", false)
            val basis = remember(engine.fullPlayerReferenceTravelPx) {
                ExpansionBasis(Rect(0f, 0f, 100f, 100f),
                    Rect(0f, 0f, 100f + engine.fullPlayerReferenceTravelPx, 100f), 1f)
            }
            val range = engine.fullPlayerReferenceTravelPx + 200f
            val rect = rememberMotionRect(generation to "rect", basis.startBounds, EdgeRanges(range, range, range, range))
            val scrollbar = remember(clock, generation) { ScrollbarMotion(clock) }
            DisposableEffect(scrollbar) { onDispose { scrollbar.dispose() } }
            val density = LocalDensity.current
            val windowSize = LocalWindowInfo.current.containerSize
            val insets = WindowInsets.safeDrawing
            val left = insets.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
            val top = insets.getTop(density)
            val right = insets.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
            val bottom = insets.getBottom(density)
            SideEffect {
                expansion.setBasis(basis); expansion.bind(rect)
                published.set(Owners(clock, engine, systemScale, scalar, spring, expansion, rect, basis, scrollbar, generation, pinch, scope))
                profile.set("window=$windowSize density=${density.density} fontScale=${density.fontScale} safeInsets=$left,$top,$right,$bottom")
            }
            Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
                Canvas(Modifier.requiredSize(180.dp, 120.dp).onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    check(bounds.left >= left && bounds.top >= top && bounds.right <= windowSize.width - right && bounds.bottom <= windowSize.height - bottom)
                    contentBounds.set(bounds)
                }) {
                    drawRect(Color.White)
                    drawRect(Color.Blue.copy(alpha = scrollbar.opacity()), size = androidx.compose.ui.geometry.Size(size.width / 2, size.height))
                    drawRect(Color.Red, topLeft = androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width / 2, size.height * scalar.value().coerceIn(0f, 1f)))
                }
            }
        } }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, predicate: (Owners) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (published.get()?.let(predicate) == true) return
            SystemClock.sleep(20)
        }
        fail("$label; last=${published.get()?.engine}; profile=${profile.get()}")
    }
    private fun setting(value: String?) {
        require(value == null || value.toFloatOrNull()?.let { it.isFinite() && it >= 0f } == true)
        val command = if (value == null) "settings delete global animator_duration_scale"
            else "settings put global animator_duration_scale $value"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
    private fun scale(value: Float) {
        setting(value.toString())
        await("Actual system observer and root clock receive $value") {
            it.systemScale == value && it.engine.durationScale == value && it.clock.context?.durationScale == value
        }
    }
    private fun output(name: String): File {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(context.getExternalFilesDir(null)).absolutePath, "platform-duration-scale/$name")
        directory.mkdirs()
        return directory
    }
    private fun image(directory: File, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(60)
        val bounds = checkNotNull(contentBounds.get())
        val whole = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val crop = Bitmap.createBitmap(whole, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        File(directory, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        whole.recycle()
        return crop
    }
    private fun withWindow(name: String, substituted: UiClock?, block: (File) -> Unit) {
        val original = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        require(original == null || original.toFloatOrNull()?.let { it.isFinite() && it >= 0f } == true)
        val directory = output(name)
        File(directory, "source.txt").writeText("source=${BuildConfig.SOURCE_SHA}\noriginalAnimatorScale=$original\nsubstitutedClock=${substituted != null}\n")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var failure: Throwable? = null
            try {
                scenario.onActivity { activity -> activity.setContent {
                    if (substituted == null) Scene()
                    else CompositionLocalProvider(LocalClockOwner provides substituted) { Scene() }
                } }
                await("Root owners are installed") { true }
                block(directory)
            } catch (thrown: Throwable) {
                failure = thrown
                throw thrown
            } finally {
                val restored = runCatching {
                    setting(original)
                    assertEquals(original, Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE))
                    await("Window observes restored scale") { it.systemScale == (original?.toFloatOrNull() ?: 1f) }
                }.exceptionOrNull()
                File(directory, "window.txt").writeText("${profile.get()}\nrestored=${restored == null}\n")
                if (restored != null) { if (failure != null) failure.addSuppressed(restored) else throw restored }
            }
        }
    }

    @Test fun nativeSystemScaleFeedsExactDeterministicRootControls() {
        // Test source of frames only. The real window/root supplies every context and scale.
        val harness = EngineHarness()
        try {
            withWindow("deterministic", harness.clock) { directory ->
                val receipts = File(directory, "frames.tsv")
                receipts.writeText("source\tsystem\troot\taccess\tclockIdentity\tframe\tscalarBits\tspringBits\tphaseBits\trightBits\tfadeBits\treleaseBits\n")
                for (value in listOf(0f, .5f, 1f, 2f)) {
                    scale(value)
                    val next = generation + 1
                    main { generation = next }
                    await("Fresh owners") { it.generation == next }
                    val owners = checkNotNull(published.get())
                    val control = EngineHarness()
                    try {
                        lateinit var scalar: MotionFloat
                        lateinit var spring: MotionFloat
                        lateinit var expansion: ExpandCollapse
                        lateinit var rect: MotionRect
                        var origin = 0L
                        main {
                            assertSame(harness.clock, owners.clock)
                            assertSame(owners.engine, owners.clock.context)
                            assertEquals(MotionAccess.Standard, owners.engine.access)
                            scalar = MotionFloat(control.clock, 0f, 1f)
                            spring = MotionFloat(control.clock, 0f, 1f)
                            expansion = ExpandCollapse(control.clock, StockUiSpec.expansion, false)
                            expansion.setBasis(owners.basis)
                            val r = owners.engine.fullPlayerReferenceTravelPx + 200f
                            rect = MotionRect(control.clock, owners.basis.startBounds, EdgeRanges(r, r, r, r))
                            expansion.bind(rect)
                            // Match the root's reference geometry without injecting its platform scale.
                            control.clock.configure(owners.engine.copy(durationScale = 1f), owners.clock.spec)
                            origin = harness.now
                            owners.scalar.retarget(1f, motionProfileId(MotionProfile.Control)); scalar.retarget(1f, motionProfileId(MotionProfile.Control))
                            owners.spring.retargetSpring(1f, springProfileId(SpringProfile.Strain)); spring.retargetSpring(1f, springProfileId(SpringProfile.Strain))
                            owners.expansion.target(true); expansion.target(true)
                            owners.scrollbar.reveal(); owners.scrollbar.release(.3f)
                        }
                        val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
                        val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
                        for (frame in 0..180) {
                            // Immutable values leave Main before receipt I/O and assertions.
                            val sample = AtomicReference<List<Float>>()
                            main {
                                harness.advance(.016)
                                val stamp = harness.clock.stampNow
                                sample.set(listOf(owners.scalar.valueAt(stamp), owners.spring.valueAt(stamp), owners.expansion.phase.valueAt(stamp),
                                    owners.rect.right.valueAt(stamp), owners.scrollbar.opacity(), owners.scrollbar.releaseOffset()))
                            }
                            val values = sample.get()
                            receipts.appendText(listOf(BuildConfig.SOURCE_SHA, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                                owners.engine.durationScale, owners.engine.access, System.identityHashCode(owners.clock), frame,
                                *values.map { it.toRawBits() }.toTypedArray()).joinToString("\t") + "\n")
                            val timed = if (value == 0f) Long.MAX_VALUE / 4 else (((frame + 1) * 16_000_000L) / value).toLong()
                            val referenceStamp = FrameStamp(control.now + timed, 0)
                            assertEquals(scalar.valueAt(referenceStamp).toRawBits(), values[0].toRawBits())
                            assertEquals(spring.valueAt(referenceStamp).toRawBits(), values[1].toRawBits())
                            assertEquals(expansion.phase.valueAt(referenceStamp).toRawBits(), values[2].toRawBits())
                            assertEquals(rect.right.valueAt(referenceStamp).toRawBits(), values[3].toRawBits())
                            val scrollbarTime = if (value == 0f) Long.MAX_VALUE / 4 else ((frame * 16_000_000L) / value).toLong()
                            assertEquals(fade.getValueFromNanos(scrollbarTime).toRawBits(), values[4].toRawBits())
                            assertEquals(release.getValueFromNanos(scrollbarTime).toRawBits(), values[5].toRawBits())
                            if (frame == 0) {
                                val pixels = image(directory, "scale-$value-admission")
                                if (value > 0f) assertEquals(android.graphics.Color.BLUE, pixels.getPixel(pixels.width / 4, pixels.height / 2))
                                pixels.recycle()
                            }
                        }
                        assertTrue(harness.now > origin)
                        main { scalar.dispose(); spring.dispose(); expansion.dispose() }
                    } finally { control.close() }
                }
                scale(1f)
                val live = checkNotNull(published.get())
                main { live.scrollbar.release(.3f); harness.advance(.016); harness.advance(.064) }
                scale(2f)
                val releaseReference = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
                fun liveReceipt(label: String, expectedTime: Long) {
                    val observed = AtomicReference<Float>()
                    main { harness.advance(.016); observed.set(live.scrollbar.releaseOffset()) }
                    File(directory, "$label.txt").writeText("source=${BuildConfig.SOURCE_SHA} scale=${published.get()!!.engine.durationScale} releaseBits=${observed.get().toRawBits()} expectedPlayTime=$expectedTime\n")
                    assertEquals(releaseReference.getValueFromNanos(expectedTime).toRawBits(), observed.get().toRawBits())
                }
                liveReceipt("live-one-to-two", 40_000_000)
                scale(.5f)
                liveReceipt("live-two-to-half", 192_000_000)
                scale(2f)
                val pinchOwner = checkNotNull(published.get())
                val pinchReference = TargetBasedAnimation(tween<Float>(220), Float.VectorConverter, 0f, 1f)
                main { pinchOwner.scope.launch(start = CoroutineStart.UNDISPATCHED) { pinchOwner.pinch.settle(0f, 1f) { pinchValue = it } } }
                val pinchRows = File(directory, "pinch.tsv")
                pinchRows.writeText("frame\tvalueBits\texpectedBits\n")
                for (frame in 0..28) {
                    val observed = AtomicReference<Float>()
                    main { harness.advance(.016); observed.set(pinchValue) }
                    val expected = pinchReference.getValueFromNanos(minOf(220_000_000L, frame * 8_000_000L))
                    pinchRows.appendText("$frame\t${observed.get().toRawBits()}\t${expected.toRawBits()}\n")
                    assertEquals("Pinch scales once and retains its real-frame clamp", expected.toRawBits(), observed.get().toRawBits())
                }
                main { live.scrollbar.release(.3f); harness.advance(.016) }
                scale(0f)
                val zero = AtomicReference<Float>()
                main { harness.advance(.016); zero.set(live.scrollbar.releaseOffset()) }
                File(directory, "live-zero.txt").writeText("releaseBits=${zero.get().toRawBits()}\n")
                assertEquals(0f, zero.get(), 0f)
                scale(1f)
                main { root.settings.value = root.settings.value.copy(reducedMotion = true) }
                await("App Reduced stays separate") { it.engine.access == MotionAccess.Reduced }
                main { published.get()!!.scrollbar.access(true); published.get()!!.scrollbar.reveal(); harness.advance(.016) }
                val pixels = image(directory, "app-reduced-positive-reveal")
                assertEquals(android.graphics.Color.BLUE, pixels.getPixel(pixels.width / 4, pixels.height / 2))
                pixels.recycle()
            }
        } finally { harness.close() }
    }

    @Test fun productionClockReceivesLiveZeroWithoutTakingContactOwnership() {
        withWindow("production-clock", null) { directory ->
            scale(2f)
            val owners = checkNotNull(published.get())
            main {
                owners.scalar.retarget(1f, motionProfileId(MotionProfile.Control))
                owners.expansion.contactBegin(owners.clock.stampNow)
                owners.expansion.contactMove(owners.basis.edgeTravelPx / 2, owners.clock.stampNow)
                owners.scrollbar.reveal(); owners.scrollbar.release(.3f)
            }
            scale(0f)
            await("Production clock finishes timed work on a real frame") { it.scalar.settled }
            val row = AtomicReference<String>()
            main {
                val current = checkNotNull(published.get())
                row.set("system=${current.systemScale} root=${current.engine.durationScale} access=${current.engine.access} clock=${System.identityHashCode(current.clock)} scalar=${current.scalar.value()} phase=${current.expansion.phase.value()} contact=${current.expansion.inContact} fade=${current.scrollbar.opacity()} release=${current.scrollbar.releaseOffset()}")
            }
            File(directory, "zero.txt").writeText(row.get() + "\n")
            val pixels = image(directory, "system-zero")
            pixels.recycle()
            main {
                val current = checkNotNull(published.get())
                assertSame(owners.clock, current.clock)
                assertEquals(MotionAccess.Standard, current.engine.access)
                assertEquals(1f, current.scalar.value(), 0f)
                assertTrue(current.expansion.inContact)
                assertEquals(.5f, current.expansion.phase.value(), 0f)
                assertEquals(0f, current.scrollbar.opacity(), 0f)
                assertEquals(0f, current.scrollbar.releaseOffset(), 0f)
                current.expansion.contactCancel()
            }
        }
    }
}
