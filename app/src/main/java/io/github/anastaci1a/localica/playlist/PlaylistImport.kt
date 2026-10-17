package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PlaylistImportEntry(val path: String, val track: Track?, val problem: String? = null, val nameOnly: Boolean = false)
data class PlaylistImportPreview(val name: String, val entries: List<PlaylistImportEntry>) {
    val tracks get() = entries.mapNotNull { it.track }
}

/** No file access or URL fetching. Imports resolve only to already indexed, unambiguous songs. */
object PlaylistImport {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ENTRIES = 20000
    private val plsFilePattern = Regex("(?i)^File([0-9]+)=(.*)$")
    fun parse(bytes: ByteArray, fileName: String, library: LibrarySnapshot, parentPath: String? = null): PlaylistImportPreview {
        require(bytes.size <= MAX_BYTES) { "Playlist exceeds the 5 MB limit." }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in setOf("m3u", "m3u8", "pls")) { "Choose an M3U, M3U8, or PLS playlist." }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            require(extension != "m3u8") { "This M3U8 file is not valid UTF-8." }
            bytes.toString(Charsets.ISO_8859_1)
        }
        require('\u0000' !in text) { "The playlist contains unsupported binary or UTF-16 data." }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }
        val paths = if (extension == "pls") {
            var hasHeader = false
            val files = sortedMapOf<Int, String>()
            lines.forEach { line ->
                if (line.equals("[playlist]", true)) hasHeader = true
                val match = plsFilePattern.matchEntire(line) ?: return@forEach
                val index = match.groupValues[1].toIntOrNull()
                require(index != null && index > 0 && index !in files) { "The PLS file has invalid or repeated entry numbers." }
                require(files.size < MAX_ENTRIES) { "Playlist exceeds the 20,000-song limit." }
                files[index] = match.groupValues[2].trim()
            }
            require(hasHeader) { "This is not a PLS playlist." }
            files.values.toList()
        } else buildList {
            lines.forEach { line ->
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                require(size < MAX_ENTRIES) { "Playlist exceeds the 20,000-song limit." }
                add(line)
            }
        }
        require(paths.isNotEmpty()) { "This playlist contains no songs." }
        val folders = library.folders.associate { it.uri to it.name }
        val candidates = library.tracks.map { track ->
            track to buildSet {
                add(normalize(track.relativePath))
                documentPath(track.uri)?.let { add(normalize(it)) }
                track.locations.forEach { location ->
                    add(normalize(location.relativePath))
                    folders[location.folderUri]?.let { add(normalize("$it/${location.relativePath}")) }
                    documentPath(location.uri)?.let { add(normalize(it)) }
                }
            }
        }
        val pathsIndex = mutableMapOf<String, MutableMap<String, Track>>()
        candidates.forEach { (track, aliases) -> aliases.forEach { alias ->
            var suffix = alias
            while (suffix.isNotEmpty()) {
                pathsIndex.getOrPut(suffix) { linkedMapOf() }[track.id] = track
                if ('/' !in suffix) break
                suffix = suffix.substringAfter('/')
            }
        } }
        val uriIndex = mutableMapOf<String, MutableMap<String, Track>>()
        library.tracks.forEach { track -> (listOf(track.uri) + track.locations.map { it.uri }).forEach { uri ->
            uriIndex.getOrPut(uri) { linkedMapOf() }[track.id] = track
        } }
        val namesIndex = library.tracks.groupBy { it.fileName }
        fun matching(path: String) = pathsIndex[path]?.values?.toList().orEmpty()
        val entries = paths.map { raw ->
            val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(raw)?.groupValues?.get(1)?.lowercase()
            if (scheme != null && scheme !in setOf("file", "content") && scheme.length > 1) {
                PlaylistImportEntry(raw, null, "Network and streaming entries are not supported")
            } else {
                val direct = uriIndex[raw]?.values?.toList().orEmpty()
                val decoded = if (scheme == "file") runCatching { URI(raw).path }.getOrNull() ?: raw else raw
                val path = normalize(decoded)
                val relative = scheme == null && !raw.startsWith('/') && !raw.startsWith('\\')
                val resolved = if (relative && parentPath != null) matching(normalize("$parentPath/$decoded")) else emptyList()
                var found = direct.ifEmpty { resolved }.ifEmpty { matching(path) }
                var nameOnly = direct.isEmpty() && resolved.isEmpty() && '/' !in path && found.size == 1
                if (found.isEmpty() && scheme != "content") {
                    found = namesIndex[path.substringAfterLast('/')].orEmpty().distinctBy { it.id }
                    nameOnly = found.size == 1
                }
                when (found.size) {
                    1 -> PlaylistImportEntry(raw, found.single(), nameOnly = nameOnly)
                    0 -> PlaylistImportEntry(raw, null, "Not found in the scanned library")
                    else -> PlaylistImportEntry(raw, null, "More than one indexed song matches")
                }
            }
        }
        return PlaylistImportPreview(fileName.substringBeforeLast('.'), entries)
    }

    private fun normalize(path: String): String {
        val segments = ArrayDeque<String>()
        path.replace('\\', '/').removePrefix("/").split('/').forEach { part ->
            when (part) { "", "." -> Unit; ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeLast() else segments.add(part)
                else -> segments.add(part) }
        }
        return segments.joinToString("/")
    }

    /** DocumentsUI's external-storage IDs retain the filesystem-relative path; other providers remain opaque. */
    fun documentPath(uri: String): String? {
        if (!uri.startsWith("content://com.android.externalstorage.documents/")) return null
        val id = uri.substringAfter("/document/", "").ifEmpty { return null }
        return runCatching { URLDecoder.decode(id.replace("+", "%2B"), "UTF-8").substringAfter(':') }.getOrNull()
    }
}
