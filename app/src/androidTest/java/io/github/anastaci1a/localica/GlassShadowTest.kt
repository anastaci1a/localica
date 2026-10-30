package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class GlassShadowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deferredRingPathsMaterializeForTheCurrentGeometryOnly() {
        val cache = GlassShadowGeometry()
        val bodies = listOf(RoundRect(20f, 20f, 80f, 60f, CornerRadius(8f)))
        cache.update(bodies, 12f, 2f, needRings = false)
        val body = cache.body
        assertTrue(cache.rings.isEmpty())
        cache.update(bodies, 12f, 2f)
        org.junit.Assert.assertSame(body, cache.body)
        org.junit.Assert.assertEquals(16, cache.rings.size)
        val rings = cache.rings
        cache.update(bodies, 12f, 2f, needRings = false)
        cache.update(bodies, 12f, 2f)
        org.junit.Assert.assertSame(rings, cache.rings)
        val moved = listOf(RoundRect(25f, 30f, 85f, 70f, CornerRadius(8f)))
        cache.update(moved, 20f, 3f, needRings = false)
        assertTrue(cache.rings.isEmpty())
        org.junit.Assert.assertNotSame(body, cache.body)
        cache.update(moved, 20f, 3f)
        val fresh = GlassShadowGeometry().also { it.update(moved, 20f, 3f) }
        org.junit.Assert.assertEquals(fresh.body.getBounds(), cache.body.getBounds())
        org.junit.Assert.assertEquals(fresh.rings.map { it.getBounds() }, cache.rings.map { it.getBounds() })
    }

    @Test fun uniformDrawingSkipsRingPathsAndGenericDrawingStillBuildsThem() {
        val cache = GlassShadowGeometry()
        fun render(body: RoundRect): androidx.compose.ui.graphics.ImageBitmap {
            val image = androidx.compose.ui.graphics.ImageBitmap(120, 100)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit.Density(1f), LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(image), androidx.compose.ui.geometry.Size(120f, 100f)) {
                glassShadows(listOf(body to 1f), .8f, .6f, cache)
            }
            return image
        }
        val uniform = RoundRect(30f, 30f, 90f, 70f, CornerRadius(8f))
        val image = render(uniform).toPixelMap()
        assertTrue("Primitive path must omit unused ring paths", cache.rings.isEmpty())
        assertTrue("Omitting paths must not omit the exterior shadow", image[28, 50].alpha > 0f)
        val asymmetric = RoundRect(30f, 30f, 90f, 70f, CornerRadius(8f), CornerRadius(12f),
            CornerRadius(6f), CornerRadius(10f))
        render(asymmetric)
        org.junit.Assert.assertEquals(16, cache.rings.size)
        render(uniform)
        assertTrue("New uniform geometry must discard obsolete ring paths", cache.rings.isEmpty())
    }

    @Test fun receiverWeightPreservesExteriorAndRetainedParityWithoutMotionRasterization() {
        var mode by mutableIntStateOf(0)
        var retainedMode by mutableStateOf(false)
        var shift by mutableFloatStateOf(0f)
        val field = GlassShadowReceiverField()
        lateinit var retained: RetainedGlassShadow
        compose.setContent {
            val scope = rememberCoroutineScope()
            retained = remember { RetainedGlassShadow(scope) }
            val geometry = remember { GlassShadowGeometry() }
            Box(Modifier.size(240.dp,180.dp).background(Color.White).testTag("weighted-shadow")
                .drawBehind {
                    val body = RoundRect(80.dp.toPx(),50.dp.toPx(),140.dp.toPx(),110.dp.toPx(),CornerRadius(16.dp.toPx()))
                    val home = androidx.compose.ui.geometry.Rect(120.dp.toPx(),80.dp.toPx(),180.dp.toPx(),140.dp.toPx())
                    val receiver = if (mode == 0) null else field.receiver(
                        if (mode == 2) home.translate(androidx.compose.ui.geometry.Offset(400.dp.toPx(),0f)) else home,
                        30.dp.toPx(),size,1f,1f,shift,physicalSize=home.size)
                    if (retainedMode) with(retained) { draw(listOf(body to 1f),1f,.6f,receiver) }
                    else glassShadows(listOf(body to 1f),1f,.6f,geometry,receiver=receiver)
                })
        }
        val baseline = compose.onNodeWithTag("weighted-shadow").captureToImage().toPixelMap()
        compose.runOnIdle { mode = 1 }
        val weighted = compose.onNodeWithTag("weighted-shadow").captureToImage().toPixelMap()
        val d = weighted.width/240f
        var outsideMax = 0f
        for (y in 0 until weighted.height) for (x in 0 until weighted.width) {
            if (x < 118*d || x > 182*d || y < 78*d || y > 142*d)
                outsideMax=maxOf(outsideMax,abs(weighted[x,y].red-baseline[x,y].red))
            assertTrue("Receiver must not add a second shadow",weighted[x,y].red+2f/255 >= baseline[x,y].red)
        }
        assertTrue("Ordinary exterior unchanged: $outsideMax",outsideMax <= 2f/255)
        assertTrue("Basin attenuates the moving caster",weighted[(145*d).toInt(),(105*d).toInt()].red >
            baseline[(145*d).toInt(),(105*d).toInt()].red+.005f)
        compose.runOnIdle { mode = 2 }
        val disjoint = compose.onNodeWithTag("weighted-shadow").captureToImage().toPixelMap()
        for (y in 0 until disjoint.height) for (x in 0 until disjoint.width)
            assertTrue("Disjoint receiver keeps original path",abs(disjoint[x,y].red-baseline[x,y].red)<=1f/255)
        compose.runOnIdle { mode=1; retainedMode=true }
        compose.waitUntil(5000) { retained.rasterizations > 0 }
        val cached = compose.onNodeWithTag("weighted-shadow").captureToImage().toPixelMap()
        var difference = 0f; var maximum = 0f
        for (y in 0 until cached.height) for (x in 0 until cached.width) {
            val delta=abs(cached[x,y].red-weighted[x,y].red);difference+=delta;maximum=maxOf(maximum,delta)
        }
        assertTrue("Weighted retained/direct parity: max=$maximum",maximum<.04f && difference/(cached.width*cached.height)<.003f)
        val maskCount = field.rasterizations
        for (motion in listOf(-13f,7f,19f,0f)) {
            compose.runOnIdle { shift=motion }
            compose.onNodeWithTag("weighted-shadow").captureToImage()
        }
        org.junit.Assert.assertEquals("Motion only remaps immutable receiver",maskCount,field.rasterizations)
        compose.runOnIdle {
            val home=androidx.compose.ui.geometry.Rect(100f,200f,1300f,230f)
            val first=requireNotNull(field.receiver(home,15f,androidx.compose.ui.geometry.Size(180f,90f),1f,1f,0f))
            val count=field.rasterizations
            assertTrue("Total padded mask budget",first.mask.width<=96 && first.mask.height<=96)
            assertTrue("Narrow home retains enough cross-section samples",first.mask.height>=21)
            val mask=first.mask.toPixelMap()
            for (x in 0 until mask.width) {
                org.junit.Assert.assertEquals(1f,mask[x,0].alpha,0f)
                org.junit.Assert.assertEquals(1f,mask[x,2].alpha,0f)
                org.junit.Assert.assertEquals(1f,mask[x,mask.height-1].alpha,0f)
            }
            for (sx in listOf(.7f,1f,1.3f)) for (sy in listOf(.8f,1f,1.2f)) for (dx in listOf(-13f,0f,19f)) {
                val next=requireNotNull(field.receiver(home.translate(androidx.compose.ui.geometry.Offset(dx,0f)),15f,
                    androidx.compose.ui.geometry.Size(180f,90f),sx,sy,dx,physicalSize=home.size))
                org.junit.Assert.assertSame("Caster motion reuses mask",first.mask,next.mask)
            }
            org.junit.Assert.assertEquals("27 transformed samples do not rasterize",count,field.rasterizations)
        }
    }

    @Test fun originWellIsMaskedByActualCasterWithoutFadingAndReusesStationaryRaster() {
        var casterX by mutableFloatStateOf(0f)
        val well = GlassOriginWell()
        compose.setContent {
            Box(Modifier.size(220.dp, 120.dp).background(Color(0xFF808080)).testTag("origin-well")
                .drawBehind {
                    val home = androidx.compose.ui.geometry.Rect(30.dp.toPx(),30.dp.toPx(),150.dp.toPx(),78.dp.toPx())
                    val caster = RoundRect(home.translate(androidx.compose.ui.geometry.Offset(casterX.dp.toPx(),0f)),
                        androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
                    with(well) { draw(home,24.dp.toPx(),caster,1f) }
                })
        }
        val closed = compose.onNodeWithTag("origin-well").captureToImage().toPixelMap()
        compose.runOnIdle { casterX = 140f }
        val open = compose.onNodeWithTag("origin-well").captureToImage().toPixelMap()
        val d = open.width / 220f
        val x = (90*d).toInt(); val y = (54*d).toInt()
        assertTrue("Vacant basin shades its receiver",open[x,y].red < closed[x,y].red-.01f)
        val count = compose.runOnIdle { well.rasterizations }
        for (position in listOf(70f,20f,100f,0f)) {
            compose.runOnIdle { casterX = position }
            compose.onNodeWithTag("origin-well").captureToImage()
        }
        val restored = compose.onNodeWithTag("origin-well").captureToImage().toPixelMap()
        assertTrue("Covered receiver has no shadow residue",abs(restored[x,y].red-closed[x,y].red) < .001f)
        compose.runOnIdle { org.junit.Assert.assertEquals("Moving caster must not rerasterize",count,well.rasterizations) }
    }

    @Test fun singleBodyPrimitivePreservesTheExistingPathShadowAtTheSameNativeOrigin() {
        var reference by mutableStateOf(true)
        var sample by mutableIntStateOf(0)
        compose.setContent {
            val geometry = remember { GlassShadowGeometry() }
            Box(Modifier.size(240.dp, 180.dp).background(Color.White).testTag("primitive-shadow")
                .drawBehind {
                    val dx = if (sample % 2 == 0) 0f else .375f
                    val radius = if (sample < 2) CornerRadius(22.dp.toPx())
                        else CornerRadius(30.dp.toPx(), 12.dp.toPx())
                    val body = RoundRect(48.dp.toPx() + dx, 48.dp.toPx() + dx,
                        188.dp.toPx() + dx, 118.dp.toPx() + dx, radius)
                    val opacity = if (sample == 3) .2f else 1f
                    if (!reference) glassShadows(listOf(body to opacity), .8f, .6f, geometry)
                    else {
                        // Frozen pre-primitive operation sequence, at the SAME host origin.
                        geometry.update(listOf(body), (4f + .6f * 28f).dp.toPx(), 2.dp.toPx())
                        clipPath(geometry.body,
                            androidx.compose.ui.graphics.ClipOp.Difference) {
                            geometry.rings.forEachIndexed { index, ring ->
                                val t = (16 - index) / 16f
                                val alpha = .8f * opacity * .07f * ((1f - t) * (1f - t))
                                if (alpha > 0f) drawPath(ring, Color.Black.copy(alpha = alpha))
                            }
                        }
                    }
                })
        }
        repeat(4) { index ->
            compose.runOnIdle { sample = index; reference = true }
            val expected = compose.onNodeWithTag("primitive-shadow").captureToImage().toPixelMap()
            compose.runOnIdle { reference = false }
            val actual = compose.onNodeWithTag("primitive-shadow").captureToImage().toPixelMap()
            var error = 0f
            var maximum = 0f
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val delta = abs(expected[x, y].red - actual[x, y].red)
                error += delta
                maximum = maxOf(maximum, delta)
            }
            // Same bounds/opacity criterion as the existing retained/direct gradient case.
            assertTrue("Primitive/path native shadow sample $index: max=$maximum sum=$error",
                maximum < .04f && error / (expected.width * expected.height) < .003f)
        }
    }

    @Test fun shadowOffsetMatchesOrdinaryPlacementWithoutRemeasuring() {
        var offset by mutableStateOf(IntOffset.Zero)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var measures = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(240.dp, 180.dp), Alignment.Center) {
                    Box(Modifier.size(90.dp, 44.dp).offset { offset }.testTag("ordinary-offset"))
                    Box(Modifier.layout { measurable, constraints ->
                        measures++
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }.size(90.dp, 44.dp).glassShadowOffset { offset }.testTag("shadow-offset"))
                }
            }
        }
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            val measured = compose.runOnIdle { measures }
            for (position in listOf(IntOffset(31, -17), IntOffset(-23, 11), IntOffset.Zero)) {
                compose.runOnIdle { offset = position }
                val expected = compose.onNodeWithTag("ordinary-offset").fetchSemanticsNode().boundsInRoot
                val actual = compose.onNodeWithTag("shadow-offset").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertEquals("Keep layout coordinates and RTL placement", expected, actual)
                compose.runOnIdle { org.junit.Assert.assertEquals("Offset reads belong to placement", measured, measures) }
            }
        }
    }

    @Test fun pageShadowEscapesTheClippedHeaderRow() {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides
                io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f)) {
                GlassShadowScope(Modifier.size(240.dp, 180.dp).background(Color.White).testTag("page-shadow")) {
                    Box(Modifier.padding(top = 40.dp).fillMaxWidth().height(48.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                        LiquidGlassSurface(Modifier.size(100.dp, 48.dp)) {}
                    }
                }
            }
        } }
        val pixels = compose.onNodeWithTag("page-shadow").captureToImage().toPixelMap()
        val d = pixels.width / 240f
        val below = pixels[(120 * d).toInt(), (96 * d).toInt()]
        assertTrue("Header clipping must not truncate the shared shadow: $below", below.red < .98f)
    }

    @Test fun overlappingShadowBodiesHaveUnionCoverageWithoutDoubleDarkening() {
        compose.setContent {
            Column {
                listOf(false, true).forEach { unionReference ->
                    Box(Modifier.size(220.dp, 170.dp).background(Color.White)
                        .testTag("overlap-$unionReference").drawBehind {
                            val bodies = listOf(
                                RoundRect(35.dp.toPx(), 40.dp.toPx(), 135.dp.toPx(), 100.dp.toPx(), CornerRadius(22.dp.toPx())),
                                RoundRect(85.dp.toPx(), 60.dp.toPx(), 180.dp.toPx(), 120.dp.toPx(), CornerRadius(22.dp.toPx()))
                            )
                            val geometry = GlassShadowGeometry()
                            geometry.update(bodies, 24.dp.toPx(), 2.dp.toPx())
                            // Compare intersecting silhouettes, including a duplicate,
                            // against the old Boolean union with translucent ink.
                            if (unionReference) {
                                val paths = bodies.map { body -> androidx.compose.ui.graphics.Path().apply { addRoundRect(body) } }
                                val union = androidx.compose.ui.graphics.Path.combine(
                                    androidx.compose.ui.graphics.PathOperation.Union, paths[0], paths[1])
                                drawPath(union, Color.Black.copy(alpha = .25f))
                            } else {
                                geometry.update(bodies + bodies.first(), 24.dp.toPx(), 2.dp.toPx())
                                drawPath(geometry.body, Color.Black.copy(alpha = .25f))
                            }
                        })
                }
            }
        }
        val expected = compose.onNodeWithTag("overlap-true").captureToImage().toPixelMap()
        val actual = compose.onNodeWithTag("overlap-false").captureToImage().toPixelMap()
        var error = 0f
        for (y in 0 until expected.height) for (x in 0 until expected.width)
            error += abs(expected[x, y].red - actual[x, y].red)
        assertTrue("Overlapping silhouettes must have the same filled coverage", error / (expected.width * expected.height) < .001f)
    }

    @Test fun bottomUnionDoesNotKeepAGhostOfAModalTrigger() {
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            val group = remember { GlassShadowGroup() }
            anchor = rememberGlassAnchor()
            CompositionLocalProvider(LocalBottomShadows provides group, LocalGlassAppearance provides
                io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f)) {
                Box(Modifier.size(240.dp, 160.dp).background(Color.White)
                    .bottomGlassShadowHost(group).testTag("shadow-union"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(104.dp, 52.dp).glassAnchor(anchor).bottomGlassShadow())
                }
            }
        } }
        fun darkPixels(): Int {
            val pixels = compose.onNodeWithTag("shadow-union").captureToImage().toPixelMap()
            var result = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                if (pixels[x, y].red < .98f) result++
            return result
        }
        assertTrue(darkPixels() > 100)
        compose.runOnIdle { anchor.modalPresent = true }
        assertTrue("The trigger keeps its shadow until the popup is ready", darkPixels() > 100)
        compose.runOnIdle { anchor.shadowInModal = true }
        org.junit.Assert.assertEquals("The popup owns its shadow while away from the bottom group", 0, darkPixels())
        compose.runOnIdle { anchor.modalPresent = false; anchor.shadowInModal = false }
        assertTrue("The returning trigger rejoins the union", darkPixels() > 100)
    }

    @Test fun paddedVisibilityPreservesTheWholeFieldAtEveryOpacityAndReversal() {
        var alpha by mutableFloatStateOf(1f)
        var expanded by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column {
                listOf(false, true).forEach { actual ->
                    val geometry = remember { GlassShadowGeometry() }
                    val width = if (expanded) 130.dp else 90.dp
                    val height = if (expanded) 70.dp else 44.dp
                    val radius = 22.dp
                    val x = if (expanded) 9.dp else (-13).dp
                    val y = if (expanded) (-7).dp else 11.dp
                    Box(Modifier.size(220.dp, 170.dp).background(Color.White).testTag("padded-$actual"), Alignment.Center) {
                        if (actual) Box(Modifier.size(width, height)
                            .glassShadowOffset { IntOffset(x.roundToPx(), y.roundToPx()) }.glassShadowOpacity { alpha }
                            .clearGlassShadow(.8f, .6f, radius)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)).background(Color.Red))
                        else Box(Modifier.fillMaxSize().drawBehind {
                            val w = width.roundToPx().toFloat(); val h = height.roundToPx().toFloat()
                            val left = ((size.width-w)/2).roundToInt().toFloat() + x.roundToPx() * (if (rtl) -1 else 1)
                            val top = ((size.height-h)/2).roundToInt().toFloat() + y.roundToPx()
                            val rect = RoundRect(left, top, left+w, top+h, CornerRadius(radius.toPx()))
                            // A full-scene reference has no surface-sized clipping layer.
                            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                                androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                            glassShadows(listOf(rect to 1f), .8f, .6f, geometry)
                            drawRoundRect(Color.Red, androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                                androidx.compose.ui.geometry.Size(rect.width, rect.height), CornerRadius(radius.toPx()))
                            drawContext.canvas.restore()
                        })
                    }
                }
            }
        } }
        listOf(1f, .5f, 0f, .25f, 1f, .5f, 1f, 0f, 1f).forEachIndexed { index, opacity ->
            compose.runOnIdle { alpha = opacity; expanded = index % 2 == 1; rtl = index % 3 == 1 }
            val expectedImage = compose.onNodeWithTag("padded-false").captureToImage()
            val expected = expectedImage.toPixelMap()
            val actualImage = compose.onNodeWithTag("padded-true").captureToImage()
            val actual = actualImage.toPixelMap()
            var error = 0f
            var max = 0f
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val difference = abs(expected[x,y].red - actual[x,y].red) + abs(expected[x,y].green - actual[x,y].green)
                error += difference
                max = maxOf(max, difference)
            }
            try {
                assertTrue("Padded field and foreground share opacity $opacity on reversal $index: max=$max error=$error",
                    max < .08f && error / (expected.width*expected.height) < .006f)
            } catch (failure: AssertionError) {
                try {
                    val directory = nativeEvidenceDirectory("glass-shadow-failures",
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
                    listOf("reference" to expectedImage, "actual" to actualImage).forEach { (name, image) ->
                        java.io.File(directory, "padded-$index-$name.png").outputStream().use {
                            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    java.io.File(directory, "padded-$index.txt").writeText(
                        "opacity=$opacity reversal=$index expanded=$expanded rtl=$rtl " +
                            "width=${expected.width} height=${expected.height} max=$max error=$error\n")
                } catch (diagnosticFailure: Throwable) {
                    if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                }
                throw failure
            }
        }
    }

    @Test fun retainedShadowMatchesFullGradientAfterShapeAndStrengthChange() {
        var expanded by mutableStateOf(false)
        var strength by mutableFloatStateOf(.8f)
        lateinit var retainedShadow: RetainedGlassShadow
        compose.setContent {
            val scope = rememberCoroutineScope()
            retainedShadow = remember(scope) { RetainedGlassShadow(scope) }
            Column {
                listOf(false, true).forEach { retained ->
                    val geometry = remember { GlassShadowGeometry() }
                    val radius = if (expanded) 12.dp else 22.dp
                    Box(Modifier.size(220.dp, 170.dp).background(Color.White).testTag("shadow-$retained"), Alignment.Center) {
                        Box(Modifier.size(if (expanded) 130.dp else 90.dp, if (expanded) 70.dp else 44.dp)
                            .drawBehind {
                                val bodies = listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())) to 1f)
                                if (retained) with(retainedShadow) { draw(bodies, strength, .7f) }
                                else
                                glassShadows(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())) to 1f),
                                    strength, .7f, geometry)
                            })
                    }
                }
            }
        }
        fun compare(expectedRasterizations: Int) {
            compose.waitUntil(5000) { retainedShadow.rasterizations >= expectedRasterizations }
            val referenceImage = compose.onNodeWithTag("shadow-false").captureToImage()
            val cachedImage = compose.onNodeWithTag("shadow-true").captureToImage()
            val reference = referenceImage.toPixelMap()
            val cached = cachedImage.toPixelMap()
            var error = 0f
            var maximum = 0f
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val delta = abs(reference[x, y].red - cached[x, y].red)
                error += delta
                maximum = maxOf(maximum, delta)
            }
            if (maximum >= .04f) {
                val cacheDir = nativeEvidenceDirectory("glass-shadow-failures",
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
                listOf("reference" to referenceImage, "cached" to cachedImage).forEach { (name, image) ->
                    java.io.File(cacheDir, "shadow-$name.png").outputStream().use {
                        image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            assertTrue("Cached gradient must preserve opacity and its padded extent: $maximum / $error",
                maximum < .04f && error / (reference.width * reference.height) < .003f)
        }
        compare(1)
        compose.runOnIdle { expanded = true }
        compare(2)
        compose.runOnIdle { strength = .2f }
        compare(3)
    }
}
