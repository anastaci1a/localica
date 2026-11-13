package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode

internal enum class GlassBackend { Normal, AverageFallback, FlatNone }

/** Resolve capability without replacing the persisted request or selecting a live capture. */
internal fun effectiveGlassBackend(mode: GlassRendererMode, api: Int): GlassBackend =
    when {
        mode == GlassRendererMode.FlatNone -> GlassBackend.FlatNone
        api >= 33 -> GlassBackend.Normal
        else -> GlassBackend.AverageFallback
    }
