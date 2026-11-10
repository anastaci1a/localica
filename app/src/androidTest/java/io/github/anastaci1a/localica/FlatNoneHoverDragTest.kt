package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #776: actual drag-card draws must keep the same flat surface and shadow owner. */
class FlatNoneHoverDragTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun redrawsAllocateNoGlassAndKeepOneStableShadow() {
        val events = mutableListOf<String>()
        val redraw = mutableIntStateOf(0)
        lateinit var root: CompositionData
        val surfaces = HoverGroupRenderer::class.java.getDeclaredField("surfaces").apply { isAccessible = true }
        val shadows = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun currentShadowOrNull(): Any? {
            val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HoverGroupRenderer, Boolean>())
            fun visit(table: CompositionData) {
                for (group in table.compositionGroups) {
                    group.data.filterIsInstance<HoverGroupRenderer>().forEach { owners.add(it) }
                    visit(group)
                }
            }
            visit(root)
            val actual = owners.singleOrNull()?.let { surfaces.get(it) as Map<*, *> } ?: return null
            return (actual.values.singleOrNull() as HoverSurfaceRenderer?)?.shadow
        }
        fun currentShadow(): Any = checkNotNull(currentShadowOrNull())
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val output = java.io.File(context.getExternalFilesDir(null), "flat-hover-776").apply { mkdirs() }
        val observations = org.json.JSONArray()
        val drawObservations = org.json.JSONArray()
        val requested = RetainedGlassShadow::class.java.getDeclaredField("requested").apply { isAccessible = true }
        val raster = RetainedGlassShadow::class.java.getDeclaredField("raster\$delegate").apply { isAccessible = true }
        fun shadowState(): org.json.JSONObject {
            val owner = currentShadowOrNull() as RetainedGlassShadow?
                ?: return org.json.JSONObject().put("allocated", false)
            val prepared = (raster.get(owner) as State<*>).value
            val key = prepared?.javaClass?.getDeclaredField("key")?.apply { isAccessible = true }?.get(prepared)
            return org.json.JSONObject().put("owner", System.identityHashCode(owner)).put("rasterizations", owner.rasterizations)
                .put("requestedKey", (requested.get(owner)?.toString() ?: "null")).put("preparedKeyMatches", key != null && key == requested.get(owner))
        }
        fun state() = compose.runOnIdle { shadowState().put("geometryReadCount", drawObservations.length()) }
        fun assertFreshDraw(priorCount: Int, request: Int, clockMs: Long) {
            val count = drawObservations.length()
            assertTrue("Every requested redraw must record two new geometry reads", count >= priorCount + 2)
            for (index in priorCount until count) {
                val read = drawObservations.getJSONObject(index)
                assertEquals("Each new read belongs to this redraw", request, read.getInt("redraw"))
                assertEquals("Each new read belongs to this frame", clockMs, read.getLong("clockMs"))
            }
        }
        fun redrawFrame() {
            compose.mainClock.advanceTimeByFrame()
            val (priorCount, request, clockMs) = compose.runOnIdle {
                Triple(drawObservations.length(), ++redraw.intValue, compose.mainClock.currentTime)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFreshDraw(priorCount, request, clockMs) }
        }
        fun assertPreparedDraw(observed: org.json.JSONObject) {
            assertTrue("The settled capture must have a matching prepared raster", observed.getBoolean("preparedKeyMatches"))
            val count = observed.getInt("geometryReadCount")
            assertTrue("Both actual card draws must be recorded", count >= 2)
            for (index in count - 2 until count) {
                val drawn = drawObservations.getJSONObject(index).getJSONObject("shadow")
                assertTrue("The recorded draw must use the prepared branch", drawn.getBoolean("preparedKeyMatches"))
                assertEquals(observed.getInt("owner"), drawn.getInt("owner"))
                assertEquals(observed.getString("requestedKey"), drawn.getString("requestedKey"))
                assertEquals(observed.getInt("rasterizations"), drawn.getInt("rasterizations"))
            }
        }
        fun save(name: String, bitmap: android.graphics.Bitmap) {
            java.io.File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun pixels(bitmap: android.graphics.Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { GlassWork.observer = { events.add(it) } }
            compose.setContent {
                root = currentComposer.compositionData
                CompositionLocalProvider(LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.FlatNone) },
                    LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.FlatNone),
                    LocalDensity provides Density(1f)) {
                    LocalicaTheme {
                        val rest = MediaItemOutline(Rect(40f, 48f, 240f, 112f), 12f)
                        val geometry = hoverGeometry(rest, Rect(0f, 0f, 280f, 160f), 1f)
                        val frame = HoverCardFrame({ Offset.Zero }, {
                            drawObservations.put(org.json.JSONObject().put("redraw", redraw.intValue)
                                .put("clockMs", compose.mainClock.currentTime).put("shadow", shadowState()))
                            geometry
                        }, { Offset.Zero },
                            { rest.bounds }, { rest.bounds }, { 1f }, { 0f }, { 1f })
                        HoverDragCard(Modifier.size(280.dp, 160.dp).testTag("flat-drag"), frame, null,
                            LiftShadow(1f, .6f, Color.Black), stackShadow = { 1f }) {}
                    }
                }
            }
            repeat(64) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle { events.clear(); shadows.add(currentShadow()) }
            // Keep the allocation regression independent of raster readiness. The
            // pre-fix mismatch releases both owners during this first redraw.
            redrawFrame()
            compose.runOnIdle {
                assertEquals("The first actual drag redraw must allocate no glass", emptyList<String>(), events)
            }
            compose.waitUntil(10_000) { state().getBoolean("preparedKeyMatches") }
            // A frozen test clock can retain the initial live display list after
            // background preparation. Qualify an actual prepared draw, not just
            // the asynchronously published raster, before taking the reference.
            redrawFrame()
            val beforeReference = state()
            assertPreparedDraw(beforeReference)
            compose.runOnIdle {
                fun rejects(check: () -> Unit): Boolean = try { check(); false } catch (_: AssertionError) { true }
                val count = drawObservations.length()
                val request = redraw.intValue
                val clock = compose.mainClock.currentTime
                val controls = org.json.JSONObject()
                    .put("stalePreparedReadsRejected", rejects { assertFreshDraw(count, request + 1, clock + 16) })
                    .put("wrongRequestRejected", rejects { assertFreshDraw(count - 2, request + 1, clock) })
                    .put("wrongClockRejected", rejects { assertFreshDraw(count - 2, request, clock + 16) })
                java.io.File(output, "freshness-controls.json").writeText(controls.toString(2))
                assertTrue(controls.getBoolean("stalePreparedReadsRejected"))
                assertTrue(controls.getBoolean("wrongRequestRejected"))
                assertTrue(controls.getBoolean("wrongClockRejected"))
            }
            val reference = compose.onNodeWithTag("flat-drag").captureToImage().asAndroidBitmap()
            val referencePixels = pixels(reference)
            val afterReference = state()
            assertPreparedDraw(afterReference)
            assertEquals("Native copy must preserve the settled draw state", beforeReference.toString(), afterReference.toString())
            observations.put(org.json.JSONObject().put("label", "reference").put("before", beforeReference).put("after", afterReference)
                .put("bitmapConfig", reference.config.toString()).put("colorSpace", reference.colorSpace.toString()))
            save("reference", reference)
            var changedImages = 0
            repeat(24) { index ->
                redrawFrame()
                val before = state()
                assertPreparedDraw(before)
                val image = compose.onNodeWithTag("flat-drag").captureToImage().asAndroidBitmap()
                val actual = pixels(image)
                val after = state()
                assertPreparedDraw(after)
                assertEquals("Native copy must preserve the settled draw state", before.toString(), after.toString())
                observations.put(org.json.JSONObject().put("label", index).put("before", before).put("after", after)
                    .put("differentArgbPixels", actual.indices.count { actual[it] != referencePixels[it] })
                    .put("bitmapConfig", image.config.toString()).put("colorSpace", image.colorSpace.toString()))
                save("frame-$index", image)
                if (!reference.sameAs(image)) changedImages++
                compose.runOnIdle { shadows.add(currentShadow()) }
            }
            java.io.File(output, "observations.json").writeText(observations.toString(2))
            java.io.File(output, "draw-observations.json").writeText(drawObservations.toString(2))
            compose.runOnIdle {
                assertEquals("Every actual drag redraw is flat; no optical allocation or preparation", emptyList<String>(), events)
                assertEquals("The same shadow survives every drag frame", 1, shadows.size)
                assertEquals("Unchanged geometry has an exactly stable native shadow", 0, changedImages)
            }
        } finally {
            compose.runOnIdle { GlassWork.observer = null }
            compose.mainClock.autoAdvance = true
        }
    }
}
