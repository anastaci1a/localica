package io.github.anastaci1a.localica

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/** Provider identity only. A tree access URI is deliberately not part of equality. */
internal data class NavigationScaleIdentity(val authority: String, val documentId: String)

/**
 * One requested queue, not an existing queue or persisted playback state. There are no fabricated
 * queue/entry IDs. The catalog factory requests its first 100 identities in ascending index order;
 * that is a deterministic selection rule, not a representative musical distribution.
 */
internal class NavigationScaleQueueRequest internal constructor(identities: List<NavigationScaleIdentity>) {
    val orderedIdentities: List<NavigationScaleIdentity> = immutableNavigationList(identities)
    val desiredCursorOrdinal: Int = 50
    val desiredPositionMs: Long = 0L
    val requestedPaused: Boolean = true

    init {
        require(orderedIdentities.size == NavigationScaleCatalog.QUEUE_ENTRY_COUNT)
        require(orderedIdentities.distinct().size == orderedIdentities.size)
    }
}

/**
 * Platform-free SOURCE-ONLY planning contract. Operator input preference is still pending.
 * These 13,313 identities are not files, scanned rows, grants or an installed fixture. The authority
 * is supplied by the caller; accepting it does not prove provider or installation ownership.
 *
 * Every planned identity binds to the SAME existing queue-tone.mp3 bytes as a synthetic control.
 * No per-track tags, artwork, representative distributions, readability or persisted state are
 * inferred. Tree aliases below describe URI syntax only, not granted access or actual ancestry.
 */
internal class NavigationScaleCatalog(val authority: String) {
    init {
        require(AUTHORITY.matches(authority)) { "Require a lowercase, unambiguous provider authority" }
    }

    val inputPreferencePending: Boolean = true
    val repeatedAssetName: String = "queue-tone.mp3"
    val repeatedAssetSha256: String = "93b3ad40749d4b57cba6e5bb77ffd697b36c04ba007c04f036ac26a72953f7f2"
    val assetProvenancePath: String = "app/src/androidTest/assets/README.txt"
    val assetProvenance: String = "Original 20-second quiet sine tone; identical bytes repeated for all planned identities."

    val identities: List<NavigationScaleIdentity> = immutableNavigationList(
        List(TRACK_COUNT) { NavigationScaleIdentity(authority, "$DOCUMENT_PREFIX$it") }
    )

    fun identityAt(index: Int): NavigationScaleIdentity {
        require(index in 0 until TRACK_COUNT) { "Catalog index outside 0..${TRACK_COUNT - 1}" }
        return identities[index]
    }

    /** Strict own-namespace parser; document IDs remain opaque, never filesystem paths. */
    fun indexOfDocumentId(documentId: String): Int {
        val match = DOCUMENT_ID.matchEntire(documentId)
            ?: throw IllegalArgumentException("Malformed catalog document ID")
        val index = match.groupValues[1].toInt()
        require(index < TRACK_COUNT) { "Catalog document ID outside the planned catalog" }
        return index
    }

    fun canonicalUri(index: Int): String =
        "content://$authority/document/${encode(identityAt(index).documentId)}"

    fun treeUri(treeDocumentId: String): String {
        requireTreeAlias(treeDocumentId)
        return "content://$authority/tree/${encode(treeDocumentId)}"
    }

    fun treeAccessUri(index: Int, treeDocumentId: String): String =
        "${treeUri(treeDocumentId)}/document/${encode(identityAt(index).documentId)}"

    /**
     * Accept only this catalog's canonical/document or two planned tree/document forms. Percent
     * encoding aliases normalize to the same decoded identity; query/fragment/extra path segments,
     * other authorities, other tree namespaces and malformed/out-of-range document IDs fail closed.
     * This pure parse does not query a ContentResolver or assert that the URI can be opened.
     */
    fun identityFromUri(value: String): NavigationScaleIdentity {
        val uri = URI.create(value)
        require(uri.scheme == "content" && !uri.isOpaque && uri.rawAuthority == authority &&
            uri.rawQuery == null && uri.rawFragment == null) { "Not an owned catalog URI shape" }
        val segments = (uri.rawPath ?: "").split('/')
        val encodedId = when {
            segments.size == 3 && segments[0].isEmpty() && segments[1] == "document" -> segments[2]
            segments.size == 5 && segments[0].isEmpty() && segments[1] == "tree" && segments[3] == "document" -> {
                requireTreeAlias(decode(segments[2]))
                segments[4]
            }
            else -> throw IllegalArgumentException("Not a catalog document URI")
        }
        return identityAt(indexOfDocumentId(decode(encodedId)))
    }

    fun singleQueueRequest(): NavigationScaleQueueRequest =
        NavigationScaleQueueRequest(identities.take(QUEUE_ENTRY_COUNT))

    private fun requireTreeAlias(documentId: String) {
        require(documentId == ROOT_DOCUMENT_ID || documentId == OVERLAP_DOCUMENT_ID) {
            "Unknown planned tree alias"
        }
    }

    companion object {
        const val TRACK_COUNT = 13_313
        const val QUEUE_ENTRY_COUNT = 100
        const val NAMESPACE = "localica-navigation-scale-v1"
        const val ROOT_DOCUMENT_ID = "$NAMESPACE:tree:root"
        const val OVERLAP_DOCUMENT_ID = "$NAMESPACE:tree:overlap"
        private const val DOCUMENT_PREFIX = "$NAMESPACE:track:"
        private val DOCUMENT_ID = Regex("${Regex.escape(DOCUMENT_PREFIX)}(0|[1-9][0-9]{0,4})")
        private val AUTHORITY = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}

private fun <T> immutableNavigationList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
