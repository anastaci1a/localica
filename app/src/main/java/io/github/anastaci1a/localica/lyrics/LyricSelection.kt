package io.github.anastaci1a.localica.lyrics

/** Resolve a saved selection, including its legacy alias, before the first available presentation. */
internal fun selectedLyricSource(sources: List<LyricSource>, chosen: String?): LyricSource? =
    sources.firstOrNull { it.id == chosen }
        ?: chosen?.takeIf { it.endsWith(":plain") }?.removeSuffix(":plain")?.let { original ->
            sources.firstOrNull { it.id == original && it.document.synced }
        } ?: sources.firstOrNull()
