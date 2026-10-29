package io.github.anastaci1a.localica

import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*

/** Explicit opt-in for this isolated same-APK comparison. No production override. */
internal fun rendererCostMode(): GlassRendererMode {
    check(Build.VERSION.SDK_INT == 36) { "Renderer comparison requires API 36" }
    return when (val mode = InstrumentationRegistry.getArguments().getString("rendererCostMode")) {
        "Modern" -> GlassRendererMode.Modern
        "Legacy" -> GlassRendererMode.Legacy
        else -> error("Explicit Modern/Legacy rendererCostMode required, got $mode")
    }
}

@Composable
internal fun RendererCostTheme(content: @Composable () -> Unit) {
    val mode = remember { rendererCostMode() }
    val runtime = remember { GlassRendererRuntime(mode) }
    val reported = remember { booleanArrayOf(false) }
    CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
        LocalicaTheme {
            content()
            SideEffect {
                check(runtime.requested == mode) { "Fixture changed requested backend" }
                check(runtime.generation.backend.name == mode.name) { "Effective backend differs" }
                check(runtime.generation.epoch == 0L) { "Unexpected backend transition" }
                if (!reported[0]) {
                    val receipt = "requested=$mode,effective=${runtime.generation.backend},epoch=${runtime.generation.epoch},api=${Build.VERSION.SDK_INT}"
                    android.util.Log.i("LocalicaRendererCost", receipt)
                    InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                        putString("renderer_cost_backend", receipt)
                    })
                    reported[0] = true
                }
            }
        }
    }
}
