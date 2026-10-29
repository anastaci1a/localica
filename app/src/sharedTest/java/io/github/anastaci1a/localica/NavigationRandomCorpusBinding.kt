package io.github.anastaci1a.localica

import java.util.Collections

/** Declared manifest values, not observed files, decoded audio or extracted artwork. */
internal data class NavigationRandomCorpusTrack(
    val index: Int,
    val documentId: String,
    val relativeMp3Path: String,
    val declaredFileBytes: Long,
    val fileSha256: String,
    val audioSha256: String,
    val coverRelativePath: String,
    val artworkSha256: String,
)

/**
 * Platform-free shape/binding for the generated unique-audio variant, NOT the repeated-tone
 * NavigationScaleCatalog control. Only its planned identity syntax/count are reused below.
 * The declared manifest digest is retained, never computed or compared with manifest bytes.
 *
 * Descriptors are copied and ordered by index. Path checks are lexical: they neither resolve a
 * filesystem nor establish symlink safety, existence, readability, ownership or content hashes.
 * A shared cover path must declare one artwork hash; equal artwork hashes at different paths
 * remain possible. No image format or audio validity is inferred from names or hash strings.
 *
 * Summing the positive declared MP3 lengths must not overflow Long. That is not a budget plan
 * or enforcement of the decimal 1,000,000,000-byte peak-concurrent media/copy limit: sidecars,
 * temporary files and every generated/staged copy still need a separately measured ledger.
 * There are no access URIs, grants, installation receipts, scanned rows or queue state here.
 */
internal class NavigationRandomCorpusBinding(
    val authority: String,
    val declaredManifestSha256: String,
    tracks: List<NavigationRandomCorpusTrack>,
) {
    private val plannedCatalog = NavigationScaleCatalog(authority)
    val evidenceScope: String = "manifest-shape-and-binding-only"
    val tracks: List<NavigationRandomCorpusTrack> = run {
        require(tracks.size == NavigationScaleCatalog.TRACK_COUNT) { "Require exactly 13,313 descriptors" }
        Collections.unmodifiableList(tracks.map { it.copy() }.sortedBy { it.index })
    }

    init {
        requireSha256(declaredManifestSha256)
        val mp3Paths = HashSet<String>()
        val fileHashes = HashSet<String>()
        val audioHashes = HashSet<String>()
        val covers = HashMap<String, String>()
        var declaredMp3Bytes = 0L
        this.tracks.forEachIndexed { index, track ->
            require(track.index == index) { "Require every index 0..13,312 exactly once" }
            require(track.documentId == plannedCatalog.identityAt(index).documentId) { "Unexpected planned document ID" }
            requireRelativePath(track.relativeMp3Path)
            require(track.relativeMp3Path.endsWith(".mp3")) { "Require a lowercase MP3 path suffix" }
            require(mp3Paths.add(track.relativeMp3Path)) { "Duplicate MP3 path" }
            require(track.declaredFileBytes > 0L) { "Require a positive declared file length" }
            require(track.declaredFileBytes <= Long.MAX_VALUE - declaredMp3Bytes) { "Declared MP3 byte sum overflows Long" }
            declaredMp3Bytes += track.declaredFileBytes
            requireSha256(track.fileSha256)
            requireSha256(track.audioSha256)
            requireSha256(track.artworkSha256)
            require(fileHashes.add(track.fileSha256)) { "Duplicate declared MP3 file hash" }
            require(audioHashes.add(track.audioSha256)) { "This variant requires unique declared audio payload hashes" }
            requireRelativePath(track.coverRelativePath)
            val previous = covers.put(track.coverRelativePath, track.artworkSha256)
            require(previous == null || previous == track.artworkSha256) { "Conflicting hashes for a shared cover path" }
        }
        require(covers.keys.none { it in mp3Paths }) { "One path cannot describe both an MP3 and a cover file" }
        val allFilePaths = mp3Paths + covers.keys
        for (path in allFilePaths) {
            var slash = path.indexOf('/')
            while (slash >= 0) {
                require(path.substring(0, slash) !in allFilePaths) { "A declared file cannot also be a parent directory" }
                slash = path.indexOf('/', slash + 1)
            }
        }
    }

    /** Authority + opaque document ID only; does not assert a provider, access URI or grant. */
    fun plannedIdentityAt(index: Int): NavigationScaleIdentity = plannedCatalog.identityAt(index)

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val PATH_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        fun requireSha256(value: String) {
            require(SHA256.matches(value)) { "Require a lowercase 64-hex SHA256 declaration" }
        }

        fun requireRelativePath(value: String) {
            require(value.isNotEmpty() && value.split('/').all { PATH_COMPONENT.matches(it) }) {
                "Require relative slash-separated ASCII names without empty, dot, escape or URI components"
            }
        }
    }
}
