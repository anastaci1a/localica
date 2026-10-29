package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode

internal enum class GlassBackend { Modern, Legacy }

/** Resolve capability without replacing the persisted request or selecting a live capture. */
internal fun effectiveGlassBackend(mode: GlassRendererMode, api: Int): GlassBackend =
    if (api >= 33 && mode != GlassRendererMode.Legacy) GlassBackend.Modern else GlassBackend.Legacy
