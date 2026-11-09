package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.rememberGraphicsLayer

/** Test observer only: no state, frame requests, or retained source references. */
internal object GlassWork {
    var observer: ((String) -> Unit)? = null
    fun event(kind: String) { if (io.github.anastaci1a.localica.BuildConfig.DEBUG) observer?.invoke(kind) }
}

@Composable
internal fun rememberGlassLayer(): androidx.compose.ui.graphics.layer.GraphicsLayer {
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        GlassWork.event("layer")
        onDispose { }
    }
    return layer
}
