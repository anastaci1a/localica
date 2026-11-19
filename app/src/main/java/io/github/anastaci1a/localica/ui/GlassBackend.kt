package io.github.anastaci1a.localica.ui

internal enum class GlassBackend { Normal, AverageFallback }

internal fun effectiveGlassBackend(api: Int): GlassBackend =
    if (api >= 33) GlassBackend.Normal else GlassBackend.AverageFallback
