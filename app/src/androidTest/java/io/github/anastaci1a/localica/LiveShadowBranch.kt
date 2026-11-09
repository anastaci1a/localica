package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import io.github.anastaci1a.localica.ui.RetainedGlassShadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** Test-only branch pin. Cancel each cache's dedicated preparation scope before its first draw.
 * The production native drawing path and its material inputs are unchanged. Slot inspection also
 * visits registered lazy subcompositions. No shadow pixels, strength, or raster fields are edited. */
@Composable
internal fun Modifier.pinLiveShadowBranch(): Modifier {
    val root = currentComposer.compositionData
    val tables = LocalInspectionTables.current
    val scopeField = remember {
        RetainedGlassShadow::class.java.getDeclaredField("scope").apply { isAccessible = true }
    }
    val seen = remember { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RetainedGlassShadow, Boolean>()) }
    return drawWithContent {
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
        drawContent()
        check(seen.all { it.rasterizations == 0 }) { "Live-branch pin admitted a prepared shadow" }
        android.util.Log.i("PERF2_SHADOW_PIN", "branch=live caches=${seen.size} rasterizations=0")
    }
}
