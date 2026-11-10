package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.anastaci1a.localica.ui.RetainedGlassShadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** Test-only owner. Every cache keeps its production geometry, raster and native drawing code.
 * Only its dedicated preparation scope is cancelled, with strict before/after guards. */
internal class LiveShadowBranchPin {
    private lateinit var root: CompositionData
    private var tables: Set<CompositionData>? = null
    private val scopeField = RetainedGlassShadow::class.java.getDeclaredField("scope").apply { isAccessible = true }
    private val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RetainedGlassShadow, Boolean>())
    val cacheCount get() = seen.size

    fun bind(root: CompositionData, tables: Set<CompositionData>?) { this.root = root; this.tables = tables }

    /** Main-thread only, after composition AND lazy layout apply. Unlike an outer draw callback,
     * this visits new independently retained/lazy children even when their parent did not redraw. */
    fun pinApplied() {
        fun inspect(table: CompositionData) {
            for (group in table.compositionGroups) {
                for (value in group.data) if (value is RetainedGlassShadow) {
                    check(value.rasterizations == 0) { "Shadow prepared before live-branch pin" }
                    if (seen.add(value)) (scopeField.get(value) as CoroutineScope).cancel()
                }
                inspect(group)
            }
        }
        inspect(root)
        tables?.toList()?.filter { it !== root }?.forEach(::inspect)
        assertLive()
    }

    fun assertLive() {
        check(seen.all { it.rasterizations == 0 }) { "Live-branch pin admitted a prepared shadow" }
        android.util.Log.i("PERF2_SHADOW_PIN", "branch=live caches=${seen.size} rasterizations=0")
    }
}

@Composable
internal fun Modifier.pinLiveShadowBranch(pin: LiveShadowBranchPin? = null): Modifier {
    val owner = pin ?: remember { LiveShadowBranchPin() }
    owner.bind(currentComposer.compositionData, LocalInspectionTables.current)
    return drawWithContent { owner.pinApplied(); drawContent(); owner.assertLive() }
}

/** TestComposeRules uses StandardTestDispatcher for effects, including rememberCoroutineScope.
 * Advancing at most one16ms frame, then pinning every applied cache, precedes delay(48).
 * Native readback and host time do not advance this virtual preparation scheduler. */
internal fun advanceLiveShadowFrame(compose: ComposeContentTestRule, pin: LiveShadowBranchPin) {
    check(!compose.mainClock.autoAdvance) { "Live-shadow fixture must own every virtual frame" }
    val before = compose.mainClock.currentTime
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    check(compose.mainClock.currentTime - before in 1L..16L) { "Uncontrolled shadow scheduler advance" }
    compose.runOnIdle { pin.pinApplied() }
}
