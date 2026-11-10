package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.RetainedGlassShadow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production preparation scopes and48ms delay, including a newly applied lazy child.
 * The negative control must trip the same strict guard once preparation is allowed to finish. */
class ShadowSchedulingOracleTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun everyAppliedCacheIsPinnedBeforePreparationIncludingNewLazyChildren() = fixture(false)
    @Test fun latePinRejectsAnActuallyPreparedCache() = fixture(true)

    private fun fixture(late: Boolean) {
        val pin = LiveShadowBranchPin()
        val caches = mutableListOf<RetainedGlassShadow>()
        var showLazy by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val tables = remember { mutableSetOf<CompositionData>() }
            pin.bind(currentComposer.compositionData, tables)
            CompositionLocalProvider(LocalInspectionTables provides tables) {
                Column {
                    Shadow { if (it !in caches) caches += it }
                    LazyColumn(Modifier.width(48.dp).height(120.dp)) {
                        if (showLazy) item { Shadow { if (it !in caches) caches += it } }
                    }
                }
            }
        }
        try {
            if (late) {
                // Deliberately omit the pin and cross the real production delay.
                compose.mainClock.advanceTimeBy(96)
                compose.waitForIdle()
                compose.waitUntil(10000) { compose.runOnIdle { caches.any { it.rasterizations > 0 } } }
                var rejected = false
                try { compose.runOnIdle { pin.pinApplied() } }
                catch (error: IllegalStateException) {
                    if (error.message != "Shadow prepared before live-branch pin") throw error
                    rejected = true
                }
                assertTrue("Late-pin negative control must hit the original strict guard", rejected)
            } else {
                advanceLiveShadowFrame(compose, pin)
                compose.runOnIdle { assertEquals(1, caches.size); assertTrue(pin.cacheCount >= 1); showLazy = true }
                advanceLiveShadowFrame(compose, pin)
                compose.runOnIdle { assertEquals("New lazy cache must actually apply", 2, caches.size); assertTrue(pin.cacheCount >= 2) }
                repeat(12) { advanceLiveShadowFrame(compose, pin) }
                compose.runOnIdle { assertTrue("Preparation remained cancelled beyond48ms", caches.all { it.rasterizations == 0 }) }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Composable private fun Shadow(created: (RetainedGlassShadow) -> Unit) {
        val scope = rememberCoroutineScope()
        val cache = remember(scope) { RetainedGlassShadow(scope) }
        SideEffect { created(cache) }
        Box(Modifier.size(40.dp).drawBehind {
            with(cache) { draw(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(12.dp.toPx())) to 1f), 1f, 1f) }
        })
    }
}
