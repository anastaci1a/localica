package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import io.github.anastaci1a.localica.ui.engine.GlassWork as WorkOwner
import io.github.anastaci1a.localica.ui.engine.rememberGlassLayer as rememberOwnedLayer

/** Component access to the engine-owned, debug-only work observer. */
internal object GlassWork {
    var observer: ((String) -> Unit)?
        get() = WorkOwner.observer
        set(value) { WorkOwner.observer = value }
    fun event(kind: String) = WorkOwner.event(kind)
}

@Composable internal fun rememberGlassLayer() = rememberOwnedLayer()
