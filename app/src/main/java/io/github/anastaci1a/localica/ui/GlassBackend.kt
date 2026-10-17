package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.anastaci1a.localica.navigation.GlassRendererMode

internal enum class GlassBackend { Modern, Legacy }

internal fun effectiveGlassBackend(mode: GlassRendererMode, api: Int): GlassBackend =
    if (api >= 33 && mode != GlassRendererMode.Legacy) GlassBackend.Modern else GlassBackend.Legacy

// Retained popup contexts share the same live policy cell as their originating page.
internal val LocalGlassBackend = staticCompositionLocalOf<State<GlassBackend>?> { null }

@Composable
internal fun glassBackend(): GlassBackend = LocalGlassBackend.current?.value
    ?: effectiveGlassBackend(LocalGlassAppearance.current.renderer, Build.VERSION.SDK_INT)

/** Keep an existing live material while the requested compatible capture prepares. */
internal fun useModernGlass(backend: GlassBackend, api: Int, legacyReady: Boolean): Boolean =
    api >= 33 && (backend == GlassBackend.Modern || !legacyReady)
