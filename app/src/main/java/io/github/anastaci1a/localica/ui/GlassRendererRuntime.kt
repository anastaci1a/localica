package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.navigation.GlassRendererMode

/** A return to the same backend is a new generation, not permission to reuse retired pixels. */
internal data class GlassRendererGeneration(val backend: GlassBackend, val epoch: Long)

internal fun nextGlassRendererGeneration(current: GlassRendererGeneration,
    requested: GlassRendererMode, api: Int): GlassRendererGeneration {
    val backend = effectiveGlassBackend(requested, api)
    return if (backend == current.backend) current else GlassRendererGeneration(backend, current.epoch + 1)
}

/** One UI-thread owner above all retained windows. Captured locals retain this reference,
 * not the mode that happened to be selected when a conditional caller was mounted. */
@Stable
internal class GlassRendererRuntime(initial: GlassRendererMode, private val api: Int = Build.VERSION.SDK_INT) {
    var requested by mutableStateOf(initial)
        private set
    var generation by mutableStateOf(GlassRendererGeneration(effectiveGlassBackend(initial, api), 0))
        private set

    fun request(mode: GlassRendererMode) {
        requested = mode
        generation = nextGlassRendererGeneration(generation, mode, api)
    }
}

internal val LocalGlassRendererRuntime = staticCompositionLocalOf<GlassRendererRuntime?> { null }

internal fun defaultGlassRendererGeneration() =
    GlassRendererGeneration(effectiveGlassBackend(GlassRendererMode.Normal, Build.VERSION.SDK_INT), 0)

@Composable
internal fun currentGlassRendererGeneration(): GlassRendererGeneration =
    LocalGlassRendererRuntime.current?.generation ?: remember { defaultGlassRendererGeneration() }

/** Retargeting preserves the current material blend while surface geometry stays mounted. */
@Composable
internal fun glassAverageProgress(): State<Float> = androidx.compose.animation.core.animateFloatAsState(
    if (currentGlassRendererGeneration().backend != GlassBackend.Normal) 1f else 0f,
    androidx.compose.animation.core.spring(1f, 450f), label = "Glass render mode")

/** Counts optical work at invocation, without retaining scenes or scheduling frames. */
internal val LocalGlassOpticalWork = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Only the legacy sampling mode retains its resting-only control exception. */
@Composable
internal fun requestedSingleToneBlur(): Boolean =
    (LocalGlassRendererRuntime.current?.requested ?: LocalGlassAppearance.current.renderer) == GlassRendererMode.SingleToneBlur

/** Flat mode bypasses the complete glass graph, including allocation and preparation. */
@Composable
internal fun flatGlass(): Boolean = currentGlassRendererGeneration().backend == GlassBackend.FlatNone

internal fun flatGlassColor(dark: Boolean) =
    if (dark) androidx.compose.ui.graphics.Color(0xFF242528) else androidx.compose.ui.graphics.Color.White

@Composable
internal fun flatGlassColor(): androidx.compose.ui.graphics.Color =
    flatGlassColor(androidx.compose.material3.MaterialTheme.colorScheme.onSurface.red > .5f)
