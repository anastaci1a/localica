package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * One immutable version of a record list, owned by the data layer. The menu reads it in place: it never copies the
 * entries and never builds its own id index. [indexOf] is the owner's existing key lookup; the per-index readers are
 * called for visible and prefetched items only, so they must not allocate in proportion to [size].
 */
interface RecordSource {
    val sourceId: String
    /** Changes whenever any id, order or shown content changes. */
    val version: Long
    val size: Int
    fun id(index: Int): String
    fun title(index: Int): String
    fun subtitle(index: Int): String? = null
    fun icon(index: Int): MenuIcon? = null
    /** The position of [id], or -1. */
    fun indexOf(id: String): Int
}

/** Proof that one source version was checked: its ids are globally unique. Admission compares it in constant time. */
class RecordCertificate internal constructor(val sourceId: String, val version: Long, val size: Int) {
    override fun equals(other: Any?) = other is RecordCertificate && sourceId == other.sourceId && version == other.version && size == other.size
    override fun hashCode() = 31 * (31 * sourceId.hashCode() + version.hashCode()) + size
    override fun toString() = "$sourceId@$version[$size]"
}

sealed interface RecordSnapshotResult {
    class Accepted(val snapshot: RecordSnapshot) : RecordSnapshotResult
    /** Diagnostics carry the source and a count, never entry ids or text. */
    class Rejected(val sourceId: String, val version: Long, val duplicateIds: Int, val blankIds: Int) : RecordSnapshotResult
}

/**
 * An admitted record list (#757, DESIGN 3.2, agent-19 M0 ruling 3): a versioned read-only handle over a
 * [RecordSource]. It holds the source reference and its certificate and nothing else, so the memory the menu adds
 * does not depend on the list size; entry content is read from the source when an item becomes visible.
 *
 * Its ids are unique across the whole source, proved once per version by [validate] before any lazy item can
 * consume a key. Content equality of the owning row is the certificate, so a page rebuild never scans the list.
 * A state holder builds it when the data version changes, not inside `declare`.
 */
@Immutable
class RecordSnapshot private constructor(val certificate: RecordCertificate, private val source: RecordSource) {
    val size: Int get() = certificate.size
    fun id(index: Int): String = source.id(index)
    fun title(index: Int): String = source.title(index)
    fun subtitle(index: Int): String? = source.subtitle(index)
    fun iconKey(index: Int): String? = source.icon(index)?.key
    fun indexOf(id: String): Int = source.indexOf(id).let { if (it in 0 until size) it else -1 }
    operator fun contains(id: String): Boolean = indexOf(id) >= 0
    override fun equals(other: Any?) = other is RecordSnapshot && certificate == other.certificate
    override fun hashCode() = certificate.hashCode()

    companion object {
        /**
         * Complete validation in constant memory: every position must be the one the source's own index reports for
         * its id, which two positions sharing an id cannot both satisfy. Suspends on [context] (not the main thread).
         */
        suspend fun validate(source: RecordSource, context: CoroutineContext = Dispatchers.Default): RecordSnapshotResult =
            withContext(context) { check(source) { coroutineContext.ensureActive() } }

        /** The same complete validation for a caller that is already off the main thread. */
        fun validateBlocking(source: RecordSource): RecordSnapshotResult = check(source) {}

        private inline fun check(source: RecordSource, alive: () -> Unit): RecordSnapshotResult {
            val size = source.size
            var duplicates = 0
            var blanks = 0
            for (index in 0 until size) {
                if (index and 0xFFF == 0) alive()
                val id = source.id(index)
                if (id.isBlank()) blanks++ else if (source.indexOf(id) != index) duplicates++
            }
            return if (duplicates > 0 || blanks > 0 || size < 0 || source.sourceId.isBlank()) RecordSnapshotResult.Rejected(source.sourceId, source.version, duplicates, blanks)
            else RecordSnapshotResult.Accepted(RecordSnapshot(RecordCertificate(source.sourceId, source.version, size), source))
        }
    }
}
