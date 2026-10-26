package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

/** Native source registration, not a reproduction of the private white-band report. */
class NestedGlassRegistrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transformedGlassSamplesItsSceneThroughForwardReverseFitAndScrolling() {
        var fitX by mutableFloatStateOf(.55f)
        var fitY by mutableFloatStateOf(.75f)
        var commonX by mutableFloatStateOf(.88f)
        var commonY by mutableFloatStateOf(.92f)
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        lateinit var source: FrostedBackdrop
        lateinit var scene: LayoutCoordinates
        lateinit var backing: LayoutCoordinates
        lateinit var lens: LayoutCoordinates
        lateinit var viewport: LayoutCoordinates
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(
                blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                source = rememberFrostedBackdrop(Unit, dark = false, glassBlur = 0f, whiteTint = 0f)
                Box(Modifier.size(320.dp, 400.dp).testTag("nested-registration-scene")
                    .onGloballyPositioned { scene = it }) {
                    // Both recordings inherit this transform. Only the foreground has
                    // the additional nonuniform fit; common ancestry must cancel.
                    Box(Modifier.offset(10.dp, 50.dp).size(300.dp, 300.dp).graphicsLayer {
                        scaleX = commonX; scaleY = commonY
                        transformOrigin = TransformOrigin(0f, 0f)
                    }) {
                        Canvas(Modifier.fillMaxSize().onGloballyPositioned { backing = it }
                            .recordFrostedBackdrop(source)) {
                            val cell = 24.dp.toPx()
                            for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                                drawRect(cellColor(x, y), Offset(x * cell, y * cell), Size(cell, cell))
                            }
                        }
                        Column(Modifier.size(300.dp, 240.dp).clipToBounds()
                            .onGloballyPositioned { viewport = it }.verticalScroll(scroll)) {
                            Spacer(Modifier.height(48.dp))
                            LiquidGlassSurface(Modifier.padding(start = 20.dp).size(260.dp, 140.dp)
                                .graphicsLayer {
                                    scaleX = fitX; scaleY = fitY
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }.onGloballyPositioned { lens = it },
                                source = source, refraction = 0f, cornerRadius = 20.dp,
                                pressFeedback = false, shadow = false)
                            Spacer(Modifier.height(300.dp))
                        }
                    }
                    // Independent native-frame identity; never poll for passing optics.
                    PresentationMarker { stage }
                }
            }
        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.lensSnapshot != null }
        val cell = 24f * compose.density.density
        data class Phase(val x: Float, val y: Float, val scroll: Int,
            val commonX: Float = .88f, val commonY: Float = .92f)
        val phases = listOf(
            Phase(1f, 1f, 0, 1f, 1f), // Ordinary untransformed content remains unchanged.
            Phase(1f, 1f, 0), // Only the common ancestor basis changes; its origin stays fixed.
            Phase(.55f, .75f, 0), // Only the relative foreground basis changes about its origin.
            Phase(.75f, .9f, 0), // A second basis-only frame must not reuse the prior crop.
            Phase(.75f, .9f, 24), Phase(1f, 1f, 64),
            Phase(.75f, .9f, 24), Phase(.55f, .75f, 0), Phase(1f, 1f, 0, 1f, 1f))
        val sourceOwner = compose.runOnIdle { source }
        val sourceLayer = compose.runOnIdle { source.layer }
        val sourceOrigin = compose.runOnIdle { scene.localPositionOf(backing, Offset.Zero) }
        var previousPhase: Phase? = null
        var previousLensOrigin: Offset? = null
        for ((index, phase) in phases.withIndex()) {
            compose.runOnIdle {
                fitX = phase.x; fitY = phase.y
                commonX = phase.commonX; commonY = phase.commonY
                scope.launch {
                    scroll.scrollTo((phase.scroll * compose.density.density).roundToInt())
                    stage = index + 1
                }
            }
            compose.waitForIdle()
            val pixels = capturePresentedPopupPixels(compose,
                compose.onNodeWithTag("nested-registration-scene"), stage) { view }
            val samples = compose.runOnIdle {
                assertSame("Transforms retain the source owner at phase $index", sourceOwner, source)
                assertSame("Transforms retain its recording layer at phase $index", sourceLayer, source.layer)
                val nowSourceOrigin = scene.localPositionOf(backing, Offset.Zero)
                assertEquals("Source origin remains fixed at phase $index", sourceOrigin.x, nowSourceOrigin.x, .02f)
                assertEquals("Source origin remains fixed at phase $index", sourceOrigin.y, nowSourceOrigin.y, .02f)
                val nowLensOrigin = scene.localPositionOf(lens, Offset.Zero)
                val previous = previousPhase
                if (previous != null && previous.commonX == phase.commonX && previous.commonY == phase.commonY &&
                    previous.scroll == phase.scroll) {
                    val oldOrigin = requireNotNull(previousLensOrigin)
                    assertEquals("Basis-only changes keep the consumer origin at phase $index", oldOrigin.x, nowLensOrigin.x, .02f)
                    assertEquals("Basis-only changes keep the consumer origin at phase $index", oldOrigin.y, nowLensOrigin.y, .02f)
                }
                previousPhase = phase; previousLensOrigin = nowLensOrigin
                val clipped = viewport.localBoundingBoxOf(lens, clipBounds = true)
                buildList {
                    for (fy in listOf(.25f, .4f, .55f, .7f)) for (fx in listOf(.2f, .35f, .5f, .65f, .8f)) {
                        val local = Offset(lens.size.width * fx, lens.size.height * fy)
                        val inViewport = viewport.localPositionOf(lens, local)
                        if (!clipped.contains(inViewport)) continue
                        val inSource = backing.localPositionOf(lens, local)
                        // Avoid raster filtering boundaries; assert every remaining
                        // landmark, not just a center fixed point of the scale.
                        val dx = inSource.x - floor(inSource.x / cell) * cell
                        val dy = inSource.y - floor(inSource.y / cell) * cell
                        if (dx < 4f || dx > cell - 4f || dy < 4f || dy > cell - 4f) continue
                        add(scene.localPositionOf(lens, local) to
                            cellColor(floor(inSource.x / cell).toInt(), floor(inSource.y / cell).toInt()))
                    }
                }
            }
            assertTrue("Enough independently mapped interior landmarks at phase $index", samples.size >= 6)
            for ((point, expected) in samples) {
                val actual = pixels[point.x.roundToInt(), point.y.roundToInt()]
                val message = "phase=$index fit=${phase.x},${phase.y} scroll=${phase.scroll} point=$point expected=$expected actual=$actual"
                assertEquals(message, expected.red, actual.red, .08f)
                assertEquals(message, expected.green, actual.green, .08f)
                assertEquals(message, expected.blue, actual.blue, .08f)
            }
        }
    }

    @Test fun missingAndDetachedLocationsCannotBorrowIdentityRegistration() {
        var mounted by mutableStateOf(true)
        var source: FrostedBackdrop? = null
        var consumer: BackdropLocation? = null
        compose.setContent { LocalicaTheme {
            if (mounted) {
                val backing = rememberFrostedBackdrop(Unit, enabled = false)
                val location = remember { BackdropLocation() }
                source = backing; consumer = location
                Box(Modifier.size(160.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) { drawRect(Color.Blue) }
                    LiquidGlassSurface(Modifier.align(Alignment.Center).size(80.dp).backdropLocation(location),
                        source = backing, pressFeedback = false, shadow = false)
                }
            }
        } }
        compose.runOnIdle {
            val liveSource = requireNotNull(source).location
            val liveConsumer = requireNotNull(consumer)
            assertNotNull("Attached component coordinates have a valid registration", liveSource.registrationTo(liveConsumer))
            assertNull("An unplaced source is not an identity fallback", BackdropLocation().registrationTo(liveConsumer))
            assertNull("An unplaced consumer is not an identity fallback", liveSource.registrationTo(BackdropLocation()))
            mounted = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val oldSource = requireNotNull(source).location
            val oldConsumer = requireNotNull(consumer)
            assertFalse("Source recording was detached", requireNotNull(oldSource.coordinates).isAttached)
            assertFalse("Consumer was detached", requireNotNull(oldConsumer.coordinates).isAttached)
            assertNull("Detached coordinates cannot remain a valid sampling edge", oldSource.registrationTo(oldConsumer))
        }
    }

    @Test fun scaledMaterialCoverageAveragesOnlyItsVisibleFootprint() {
        org.junit.Assume.assumeTrue("Material averaging is the Android 13+ adapter", Build.VERSION.SDK_INT >= 33)
        lateinit var view: View
        var stage by mutableIntStateOf(0)
        var fit by mutableFloatStateOf(.5f)
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(
                blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                val source = rememberFrostedBackdrop(Unit, dark = false, glassBlur = 0f, whiteTint = 0f)
                SideEffect { source.materialOnly = true; source.materialCorner = 0f }
                Box(Modifier.size(320.dp, 240.dp).testTag("scaled-material-scene")) {
                    Box(Modifier.align(Alignment.Center).size(200.dp, 160.dp)) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                            drawRect(Color.Red, size = Size(size.width / 2f, size.height))
                            drawRect(Color.Blue, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                        }
                        LiquidGlassSurface(Modifier.align(Alignment.Center).requiredSize(280.dp, 100.dp)
                            .graphicsLayer { scaleX = fit; scaleY = .9f }.testTag("scaled-material-lens"),
                            source = source, refraction = 0f, cornerRadius = 16.dp,
                            pressFeedback = false, shadow = false)
                    }
                    PresentationMarker { stage }
                }
            }
        } }
        for ((index, scale) in listOf(.5f, .65f, .5f).withIndex()) {
            compose.runOnIdle { fit = scale; stage = index + 1 }
            val pixels = capturePresentedPopupPixels(compose,
                compose.onNodeWithTag("scaled-material-scene"), stage) { view }
            val bounds = compose.onNodeWithTag("scaled-material-lens").fetchSemanticsNode().boundsInRoot
            val sceneBounds = compose.onNodeWithTag("scaled-material-scene").fetchSemanticsNode().boundsInRoot
            val y = (bounds.center.y - sceneBounds.top).roundToInt()
            for (fraction in listOf(.25f, .5f, .75f)) {
                val x = (bounds.left - sceneBounds.left + bounds.width * fraction).roundToInt()
                val actual = pixels[x, y]
                assertEquals("Red/blue mean at fit=$scale fraction=$fraction", .5f, actual.red, .05f)
                assertEquals("Red/blue mean at fit=$scale fraction=$fraction", .5f, actual.blue, .05f)
                assertTrue("Material mean must not introduce green", actual.green < .03f)
            }
        }
    }

    @Test fun actualPopupKeepsMaterialOwnershipAndRegistrationDuringOpeningAndReversal() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var source: FrostedBackdrop
        var material: FrostedBackdrop? = null
        var complete: FrostedBackdrop? = null
        var buttonLocation: BackdropLocation? = null
        var popupView: View? = null
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalReducedMotion provides false, LocalMotionSpeed provides 1f) {
            Box(Modifier.fillMaxSize()) {
                source = rememberFrostedBackdrop(Unit, dark = false)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFF547D91)) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                    anchor = rememberGlassAnchor()
                    LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).padding(24.dp)
                        .size(CompactControlSize).glassAnchor(anchor).testTag("registration-trigger"))
                    GlassMorphPopup(open, anchor, { open = false }) {
                        popupView = LocalView.current
                        material = LocalFrostedBackdrop.current
                        complete = LocalModalBackdrop.current
                        val location = remember { BackdropLocation() }
                        buttonLocation = location
                        Column(Modifier.width(272.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(56.dp), pressFeedback = false)
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(56.dp).backdropLocation(location)
                                .testTag("registration-popup-button"), pressFeedback = false)
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(56.dp), pressFeedback = false)
                        }
                    }
                }
            }
        } } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open = true }
            compose.advanceMorphOpening(176, anchor) { popupView }
            val opening = verifyPopupRegistration("opening", source, material, complete, buttonLocation)
            assertTrue("Opening sample must retain intermediate expansion, not a settled replacement", opening > 0f && opening < 1f)
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            val reversed = verifyPopupRegistration("reversed", source, material, complete, buttonLocation)
            assertTrue("Reverse sample keeps a scaled retained foreground", reversed > 0f && reversed < 1f)
            val returningProgress = anchor.contact.expansion.value
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            assertTrue("The same live expansion continues back after the preserved forward velocity decays",
                anchor.contact.expansion.value < returningProgress)
            compose.mainClock.advanceTimeBy(1200)
            compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
            compose.runOnIdle { assertFalse("Source trigger is restored after retained popup retirement", anchor.hidden) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun scaledTriggerHomeUsesCapturedOpeningAndCurrentReturnFootprintsWithoutChangingLogicalBounds() {
        var open by mutableStateOf(false)
        var fitX by mutableFloatStateOf(.4f)
        var fitY by mutableFloatStateOf(.5f)
        var left by mutableStateOf(110.dp)
        var top by mutableStateOf(95.dp)
        lateinit var source: FrostedBackdrop
        lateinit var anchor: GlassAnchor
        lateinit var producer: LayoutCoordinates
        lateinit var trigger: LayoutCoordinates
        var popupView: View? = null
        var phase by mutableStateOf("opening")
        data class Draw(val phase: String, val frame: HomeBackdropSamplingFrame,
            val currentCorners: List<Offset>)
        val draws = mutableListOf<Draw>()
        fun corners(width: Float, height: Float) = listOf(Offset.Zero, Offset(width, 0f),
            Offset(width, height), Offset(0f, height))
        fun nativeCorners() = corners(trigger.size.width.toFloat(), trigger.size.height.toFloat()).map {
            producer.screenToLocal(trigger.localToScreen(it))
        }
        fun assertCorners(label: String, expected: List<Offset>, actual: List<Offset>) {
            assertEquals("$label four corners", 4, actual.size)
            expected.zip(actual).forEach { (a, b) ->
                assertEquals("$label x", a.x, b.x, .04f)
                assertEquals("$label y", a.y, b.y, .04f)
            }
        }
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalReducedMotion provides false, LocalMotionSpeed provides 1f,
            LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f,
                contrast = 0f, shadowStrength = 0f)) {
            Box(Modifier.size(320.dp, 400.dp)) {
                // A real display-list producer, not a bitmap or an alternate readback.
                // API26 exercises its registration only; no CPU snapshot is requested.
                source = rememberFrostedBackdrop(Unit, enabled = false, dark = false, retainRaster = false)
                SideEffect { source.materialOnly = true; source.materialCorner = 0f }
                Box(Modifier.offset(16.dp, 32.dp).size(200.dp, 150.dp)) {
                    Canvas(Modifier.fillMaxSize().onGloballyPositioned { producer = it }
                        .recordFrostedBackdrop(source)) { drawRect(Color(0xFF547D91)) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                        anchor = rememberGlassAnchor()
                        LiquidGlassSurface(Modifier.offset(left, top).size(180.dp, 100.dp)
                            .graphicsLayer {
                                scaleX = fitX; scaleY = fitY
                                transformOrigin = TransformOrigin(0f, 0f)
                            }.onGloballyPositioned { trigger = it }.glassAnchor(anchor)
                            .testTag("scaled-home-trigger"), pressFeedback = false, shadow = false)
                        val composedPhase = phase
                        CompositionLocalProvider(LocalHomeBackdropSamplingObserver provides { frame ->
                            Snapshot.withoutReadObservation { draws += Draw(composedPhase, frame, nativeCorners()) }
                        }) {
                            GlassMorphPopup(open, anchor, { open = false }) {
                                popupView = LocalView.current
                                Box(Modifier.size(216.dp, 180.dp))
                            }
                        }
                    }
                }
            }
        } } }
        compose.waitForIdle()
        fun producerFootprint(label: String): List<Offset> = compose.runOnIdle {
            assertTrue("$label live producer", source.recorded && !source.layer.isReleased)
            assertTrue("$label explicitly material only", source.materialOnly)
            assertTrue(producer.isAttached && trigger.isAttached)
            assertEquals(trigger.size.width.toFloat(), anchor.bounds.width, .02f)
            assertEquals(trigger.size.height.toFloat(), anchor.bounds.height, .02f)
            val at = trigger.localToScreen(Offset.Zero)
            assertEquals("$label logical anchor origin x", at.x, anchor.bounds.left, .04f)
            assertEquals("$label logical anchor origin y", at.y, anchor.bounds.top, .04f)
            val actual = nativeCorners()
            val extent = Size(producer.size.width.toFloat(), producer.size.height.toFloat())
            fun inside(point: Offset) = point.x >= 0f && point.y >= 0f && point.x <= extent.width && point.y <= extent.height
            assertTrue("$label all actual scaled corners fit the producer", actual.all(::inside))
            val legacy = listOf(anchor.bounds.topLeft, anchor.bounds.topRight,
                anchor.bounds.bottomRight, anchor.bounds.bottomLeft).map(producer::screenToLocal)
            assertTrue("$label legacy unscaled logical footprint demonstrably overruns", legacy.any { !inside(it) })
            val registration = requireNotNull(anchor.samplingLocation.registrationToScreen())
            assertCorners("$label real trigger sampling metadata", actual,
                corners(trigger.size.width.toFloat(), trigger.size.height.toFloat()).map {
                    producer.screenToLocal(registration.map(it))
                })
            android.util.Log.i("MorphHomeFootprint", "$label producerSize=${source.size} recorded=${source.recorded} released=${source.layer.isReleased} logical=${anchor.bounds} actual=$actual legacy=$legacy")
            actual
        }
        val openingCorners = producerFootprint("initial")
        val logicalSize = compose.runOnIdle { anchor.bounds.size }
        val expansionOwner = anchor.contact.expansion
        fun verifyDraws(label: String, opening: Boolean) = compose.runOnIdle {
            if (Build.VERSION.SDK_INT >= 33) {
                // Observe all positive-weight choices after a fixed frame budget;
                // never poll until averaging happens or select only passing frames.
                val phaseDraws = draws.filter { it.phase == label }
                phaseDraws.forEach { assertTrue("$label finite bounded draw weight", it.frame.weight.isFinite() && it.frame.weight in 0f..1f) }
                val observed = phaseDraws.filter { it.frame.weight > 0f }
                assertTrue("$label has an actual home sampling Canvas witness", observed.isNotEmpty())
                for (draw in observed) {
                    val footprint = requireNotNull(draw.frame.footprint)
                    val expected = if (opening) openingCorners else draw.currentCorners
                    assertTrue("$label actual draw chooses material averaging", draw.frame.averaged)
                    assertTrue("$label home weight is bounded", draw.frame.weight <= 1f)
                    assertEquals(logicalSize.width, footprint.size.width.toFloat(), .02f)
                    assertEquals(logicalSize.height, footprint.size.height.toFloat(), .02f)
                    assertCorners("$label chosen corners", expected, requireNotNull(draw.frame.cornersInSource))
                    assertCorners("$label chosen immutable affine footprint", expected,
                        corners(footprint.size.width.toFloat(), footprint.size.height.toFloat()).map {
                            producer.screenToLocal(footprint.localToScreen.map(it))
                        })
                }
            } else {
                assertTrue("API26 has no modern FilteredBackdrop averaging witness", draws.isEmpty())
                android.util.Log.i("MorphHomeFootprint", "$label API26 producer/anchor/window evidence only; no averaged-home or pixel-consumption claim")
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open = true }
            compose.advanceMorphOpening(128, anchor) { popupView }
            compose.runOnIdle {
                val view = requireNotNull(popupView)
                assertTrue("Actual native popup owns a ready window", view.isAttachedToWindow && view.isLaidOut && view.hasWindowFocus())
                assertTrue(anchor.modalPresent && anchor.hidden && anchor.shadowInModal)
                assertTrue("Opening remains the real intermediate morph", expansionOwner.value > 0f && expansionOwner.value < 1f)
            }
            val window = requireNotNull(popupView)
            verifyDraws("opening", opening = true)
            compose.runOnIdle { phase = "opening-moved"; fitX = .55f; fitY = .3f; left = 90.dp; top = 100.dp }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val moved = producerFootprint("opening-moved")
            assertNotEquals("Live trigger genuinely moved after opening capture", openingCorners, moved)
            verifyDraws("opening-moved", opening = true)
            compose.runOnIdle { phase = "return"; open = false }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            verifyDraws("return", opening = false)
            compose.runOnIdle { phase = "return-moved"; fitX = .35f; fitY = .6f; left = 120.dp; top = 85.dp }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val returned = producerFootprint("return-moved")
            assertNotEquals("Return must follow another genuine trigger basis", moved, returned)
            verifyDraws("return-moved", opening = false)
            val before = expansionOwner.value
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("Scale changes/reversal preserve the same expansion owner", expansionOwner, anchor.contact.expansion)
                assertSame("Reversal retains its actual native popup", window, popupView)
                assertTrue("Original morph continues returning without a new clock", expansionOwner.value < before)
                assertEquals("Sampling never replaces logical morph dimensions", logicalSize, anchor.bounds.size)
            }
            compose.mainClock.advanceTimeBy(1200)
            compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
            compose.runOnIdle { assertFalse(anchor.hidden || anchor.modalPresent || anchor.shadowInModal) }
            verifyDraws("return-moved", opening = false)
            producerFootprint("restored")
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun detachedTriggerReturnRetainsItsLastMeasuredFootprintInsteadOfTheOpeningBasis() {
        var open by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var moved by mutableStateOf(false)
        var returning by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var source: FrostedBackdrop
        lateinit var producer: LayoutCoordinates
        lateinit var trigger: LayoutCoordinates
        var popupView: View? = null
        val draws = mutableListOf<Pair<Boolean, HomeBackdropSamplingFrame>>()
        fun nativeCorners(): List<Offset> = listOf(Offset.Zero,
            Offset(trigger.size.width.toFloat(), 0f),
            Offset(trigger.size.width.toFloat(), trigger.size.height.toFloat()),
            Offset(0f, trigger.size.height.toFloat())).map {
                producer.screenToLocal(trigger.localToScreen(it))
            }
        fun assertCorners(expected: List<Offset>, actual: List<Offset>) {
            assertEquals(4, actual.size)
            expected.zip(actual).forEach { (a, b) ->
                assertEquals(a.x, b.x, .04f); assertEquals(a.y, b.y, .04f)
            }
        }
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalReducedMotion provides false, LocalMotionSpeed provides 1f,
            LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f,
                contrast = 0f, shadowStrength = 0f)) {
            Box(Modifier.size(320.dp, 400.dp)) {
                source = rememberFrostedBackdrop(Unit, enabled = false, dark = false, retainRaster = false)
                SideEffect { source.materialOnly = true; source.materialCorner = 0f }
                Box(Modifier.offset(16.dp, 32.dp).size(200.dp, 150.dp)) {
                    Canvas(Modifier.fillMaxSize().onGloballyPositioned { producer = it }
                        .recordFrostedBackdrop(source)) { drawRect(Color(0xFF547D91)) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                        anchor = rememberGlassAnchor()
                        if (mounted) LiquidGlassSurface(Modifier
                            .offset(if (moved) 90.dp else 110.dp, if (moved) 100.dp else 95.dp)
                            .size(180.dp, 100.dp).graphicsLayer {
                                scaleX = if (moved) .55f else .4f
                                scaleY = if (moved) .3f else .5f
                                transformOrigin = TransformOrigin(0f, 0f)
                            }.onGloballyPositioned { trigger = it }.glassAnchor(anchor),
                            pressFeedback = false, shadow = false)
                        val returnPhase = returning
                        CompositionLocalProvider(LocalHomeBackdropSamplingObserver provides { frame ->
                            Snapshot.withoutReadObservation { draws += returnPhase to frame }
                        }) {
                            GlassMorphPopup(open, anchor, { open = false }) {
                                popupView = LocalView.current
                                Box(Modifier.size(216.dp, 180.dp))
                            }
                        }
                    }
                }
            }
        } } }
        compose.waitForIdle()
        val original = compose.runOnIdle { nativeCorners() }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open = true }
            compose.advanceMorphOpening(4000, anchor) { popupView }
            compose.runOnIdle {
                assertTrue(anchor.hidden && anchor.modalPresent)
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                moved = true
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val last = compose.runOnIdle {
                assertTrue(trigger.isAttached && producer.isAttached)
                val actual = nativeCorners()
                assertNotEquals("The trigger moved before disposal", original, actual)
                val cached = requireNotNull(anchor.lastSamplingFootprint)
                assertCorners(actual, requireNotNull(cached.cornersInSource(source.location.registrationToScreen())))
                actual
            }
            compose.runOnIdle { mounted = false }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.runOnIdle {
                assertFalse("Actual trigger coordinates are detached", trigger.isAttached)
                assertFalse(anchor.triggerMounted)
                assertTrue("The sampled producer remains live", source.recorded && !source.layer.isReleased)
                assertCorners(last, requireNotNull(anchor.lastSamplingFootprint)
                    .cornersInSource(source.location.registrationToScreen())!!)
                returning = true; open = false
            }
            // Observe a fixed native-frame horizon, never poll until the desired mapping appears.
            repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            // The observation window is not a new, shorter spring deadline.
            // Use the existing fixture's 4s unobserved endpoint budget for disposal.
            compose.mainClock.advanceTimeBy(4000); compose.waitForIdle()
            compose.runOnIdle {
                if (Build.VERSION.SDK_INT >= 33) {
                    val samples = draws.filter { it.first && it.second.weight > 0f }.map { it.second }
                    assertTrue("Detached return has actual positive-weight Canvas choices", samples.isNotEmpty())
                    samples.forEach { frame ->
                        assertTrue(frame.averaged)
                        assertTrue(frame.weight.isFinite() && frame.weight <= 1f)
                        assertCorners(last, requireNotNull(frame.cornersInSource))
                        assertCorners(last, requireNotNull(frame.footprint)
                            .cornersInSource(source.location.registrationToScreen())!!)
                    }
                } else {
                    assertTrue("API26 has no modern Canvas averaging witness", draws.isEmpty())
                    android.util.Log.i("MorphHomeFootprint", "detached return: API26 last measured producer/window metadata only; no sampling/pixel-consumption proof")
                }
                assertFalse(anchor.hidden || anchor.modalPresent || anchor.shadowInModal)
            }
            compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun verifyPopupRegistration(phase: String, source: FrostedBackdrop,
        material: FrostedBackdrop?, complete: FrostedBackdrop?, location: BackdropLocation?): Float = compose.runOnIdle {
        val panel = requireNotNull(material) { "$phase material was not mounted" }
        val consumer = requireNotNull(location) { "$phase button was not mounted" }
        val full = requireNotNull(complete) { "$phase complete-panel owner was not mounted" }
        assertNotSame("Inline lenses must not sample the full scene", source, panel)
        assertNotSame("Inline lenses must not sample foreground-containing modal capture", full, panel)
        assertTrue("Inline source explicitly owns material only", panel.materialOnly)
        assertFalse("Complete modal capture includes foreground", full.materialOnly)
        assertSame("Panel parent remains the independent scene", source, panel.parent)
        assertSame("Complete modal capture preserves its parent", source, full.parent)
        val from = requireNotNull(panel.location.coordinates)
        val to = requireNotNull(consumer.coordinates)
        assertTrue("Live source and consumer must remain attached", from.isAttached && to.isAttached)
        val mapping = requireNotNull(panel.location.registrationTo(consumer)) { "$phase registration rejected valid popup coordinates" }
        val sourcePoint = from.localPositionOf(to, Offset.Zero)
        val expectedX = from.localPositionOf(to, Offset(to.size.width.toFloat(), 0f))
        val scale = (expectedX.x - sourcePoint.x) / to.size.width
        // Independent native LayoutCoordinates are the oracle. The right and lower
        // landmarks expose the missing basis that an origin-only check cannot see.
        for (point in listOf(Offset.Zero, Offset(to.size.width * .8f, 0f),
            Offset(0f, to.size.height * .8f), Offset(to.size.width * .8f, to.size.height * .8f))) {
            val inSource = from.localPositionOf(to, point)
            val actual = mapping.map(inSource)
            assertEquals("$phase x registration at $point", point.x, actual.x, .02f)
            assertEquals("$phase y registration at $point", point.y, actual.y, .02f)
        }
        // The scene lives in the activity; the consumer lives in the Dialog.
        // Verify that the screen-space bridge does not reuse same-root offsets.
        val sceneCoordinates = requireNotNull(source.location.coordinates)
        val crossWindow = requireNotNull(source.location.registrationTo(consumer)) {
            "$phase registration rejected attached cross-window coordinates"
        }
        for (point in listOf(Offset(to.size.width * .25f, to.size.height * .25f),
            Offset(to.size.width * .75f, to.size.height * .75f))) {
            val inScene = sceneCoordinates.screenToLocal(to.localToScreen(point))
            assertTrue("$phase native cross-window oracle is finite", inScene.x.isFinite() && inScene.y.isFinite())
            val actual = crossWindow.map(inScene)
            assertEquals("$phase cross-window x registration", point.x, actual.x, .04f)
            assertEquals("$phase cross-window y registration", point.y, actual.y, .04f)
        }
        scale
    }

    private fun cellColor(x: Int, y: Int): Color = when ((x and 1) + 2 * (y and 1)) {
        0 -> Color.Red
        1 -> Color.Blue
        2 -> Color.Green
        else -> Color.Yellow
    }
}
