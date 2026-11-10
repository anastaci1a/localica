package io.github.anastaci1a.localica.lyrics

/** Resolve a saved selection, including its legacy alias, before the first available presentation. */
internal fun selectedLyricSource(sources: List<LyricSource>, chosen: String?): LyricSource? =
    sources.firstOrNull { it.id == chosen }
        ?: chosen?.takeIf { it.endsWith(":plain") }?.removeSuffix(":plain")?.let { original ->
            sources.firstOrNull { it.id == original && it.document.synced }
        } ?: sources.firstOrNull()

/** Mismatched or older saved state must initialize from the current track's prepared preference. */
internal fun restoredLyricChoice(trackId: String?, saved: Any?): String? =
    (saved as? List<*>)?.takeIf { it.size == 2 && it[0] == trackId }?.get(1) as? String
