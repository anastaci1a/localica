package io.github.anastaci1a.localica

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*

/** Explicit renderer acceptance variant; the existing popup fixture bodies stay unchanged. */
@Composable
internal fun ExplicitLegacyPopupTheme(content: @Composable () -> Unit) {
    val renderer = remember { GlassRendererRuntime(GlassRendererMode.Legacy) }
    CompositionLocalProvider(LocalGlassRendererRuntime provides renderer) {
        LocalicaTheme {
            check(LocalGlassRendererRuntime.current === renderer)
            check(renderer.requested == GlassRendererMode.Legacy)
            check(currentGlassRendererGeneration().backend == GlassBackend.Legacy)
            DisposableEffect(renderer) {
                Log.i("PopupLegacyAcceptance", "api=${Build.VERSION.SDK_INT} requested=${renderer.requested} backend=${renderer.generation.backend}")
                onDispose { }
            }
            content()
        }
    }
}
