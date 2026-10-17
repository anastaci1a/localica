package io.github.anastaci1a.localica.library

/** Display components are provider names, never parsed document identifiers. */
internal data class SongRootName(val uri: String, val components: List<String>, val provider: String? = null)

/** Minimal distinct suffixes across the complete configured root set. */
internal fun songRootLabels(roots: List<SongRootName>): Map<String, String> {
    val unique = roots.distinctBy { it.uri }
    if (unique.size <= 1) return unique.associate { it.uri to "" }
    val components = unique.associate { it.uri to it.components.filter(String::isNotBlank).ifEmpty { listOf("Music folder") } }
    val depths = unique.associate { it.uri to 1 }.toMutableMap()
    fun label(root: SongRootName) = components.getValue(root.uri).takeLast(depths.getValue(root.uri)).joinToString("/")
    while (true) {
        val collisions = unique.groupBy(::label).values.filter { it.size > 1 }
        var changed = false
        collisions.flatten().forEach { root ->
            if (depths.getValue(root.uri) < components.getValue(root.uri).size) {
                depths[root.uri] = depths.getValue(root.uri) + 1
                changed = true
            }
        }
        if (!changed) break
    }
    val labels = unique.associate { it.uri to label(it) }.toMutableMap()
    fun collisions() = unique.groupBy { labels.getValue(it.uri) }.values.filter { it.size > 1 }
    // Provider labels disambiguate unavailable ancestry without pretending to be parent folders.
    collisions().flatten().forEach { root ->
        root.provider?.takeIf(String::isNotBlank)?.let { labels[root.uri] = "${labels.getValue(root.uri)} ($it)" }
    }
    // Ordinals are stable under input reordering for the current configured-root set. Adding or
    // removing a root may coherently renumber a collision group. URI remains an internal tie-break.
    while (true) {
        val ambiguous = collisions()
        if (ambiguous.isEmpty()) break
        ambiguous.forEach { group -> group.sortedBy { it.uri }.forEachIndexed { index, root ->
            labels[root.uri] = "${labels.getValue(root.uri)} · Folder ${index + 1}"
        } }
    }
    return labels
}

/** Keep the currently selected physical location; overlapping roots never rewrite playback identity. */
internal fun songLocationText(track: Track, labels: Map<String, String>): String {
    val location = songInformationLocation(track)
    val prefix = location?.folderUri?.let(labels::get).orEmpty()
    val path = location?.relativePath ?: track.relativePath
    return if (prefix.isBlank()) path else "$prefix/$path"
}

internal fun songInformationLocation(track: Track): TrackLocation? =
    track.locations.firstOrNull { it.uri == track.uri && it.relativePath == track.relativePath }
        ?: track.locations.firstOrNull { it.uri == track.uri }

internal fun sameSongInformationLocation(candidate: Track, track: Track): Boolean {
    val location = songInformationLocation(track)
    return candidate.id == track.id && candidate.uri == (location?.uri ?: track.uri) &&
        candidate.relativePath == (location?.relativePath ?: track.relativePath)
}

/** Scan management keeps basenames; fractions distinguish duplicates without showing ancestry. */
internal fun scanFolderLabels(folders: List<FolderSelection>): Map<String, String> =
    folders.distinctBy { it.uri }.groupBy { it.name }.values.flatMap { group ->
        group.sortedBy { it.uri }.mapIndexed { index, folder ->
            folder.uri to if (group.size == 1) folder.name else "${folder.name} (${index + 1}/${group.size})"
        }
    }.toMap()
