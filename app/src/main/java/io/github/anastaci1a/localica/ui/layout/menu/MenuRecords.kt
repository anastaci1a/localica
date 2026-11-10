package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What one record shows. Plain content only; the entry's actions are bound once at the row. */
class RecordFace(val title: String, val subtitle: String? = null, val icon: MenuIcon? = null)
class RecordEntry(val id: String, val face: RecordFace)

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
 * An admitted record list (#757, DESIGN 3.2): immutable size, ids and entry content, and nothing else. Its ids are
 * unique across the whole snapshot, proved once per source version, either by construction ([fromMap]) or by a
 * complete validation off the main thread ([validate]). Content equality of the owning row is the certificate, so
 * a page rebuild never scans the list. A state holder builds it when the data version changes, not inside `declare`.
 */
@Immutable
class RecordSnapshot private constructor(val certificate: RecordCertificate, private val ids: Array<String>,
    private val titles: Array<String>, private val subtitles: Array<String?>, private val icons: Array<String?>,
    private val positions: HashMap<String, Int>) {
    val size: Int get() = certificate.size
    fun id(index: Int): String = ids[index]
    fun title(index: Int): String = titles[index]
    fun subtitle(index: Int): String? = subtitles[index]
    fun iconKey(index: Int): String? = icons[index]
    fun indexOf(id: String): Int = positions[id] ?: -1
    operator fun contains(id: String): Boolean = positions.containsKey(id)
    override fun equals(other: Any?) = other is RecordSnapshot && certificate == other.certificate
    override fun hashCode() = certificate.hashCode()

    companion object {
        /** Complete validation. Suspends on [context] (not the main thread); rejects before any lazy item can consume a key. */
        suspend fun validate(sourceId: String, version: Long, entries: List<RecordEntry>,
            context: CoroutineContext = Dispatchers.Default): RecordSnapshotResult = withContext(context) { build(sourceId, version, entries) }

        /** Unique by construction: the ids are the keys of [entries], which a map cannot repeat. */
        fun fromMap(sourceId: String, version: Long, entries: Map<String, RecordFace>): RecordSnapshotResult =
            build(sourceId, version, entries.map { RecordEntry(it.key, it.value) })

        private fun build(sourceId: String, version: Long, entries: List<RecordEntry>): RecordSnapshotResult {
            val copy = entries.toTypedArray()
            val positions = HashMap<String, Int>(copy.size * 2)
            var duplicates = 0
            var blanks = 0
            copy.forEachIndexed { index, entry ->
                if (entry.id.isBlank()) blanks++
                if (positions.put(entry.id, index) != null) duplicates++
            }
            if (duplicates > 0 || blanks > 0 || sourceId.isBlank()) return RecordSnapshotResult.Rejected(sourceId, version, duplicates, blanks)
            return RecordSnapshotResult.Accepted(RecordSnapshot(RecordCertificate(sourceId, version, copy.size),
                Array(copy.size) { copy[it].id }, Array(copy.size) { copy[it].face.title }, Array(copy.size) { copy[it].face.subtitle },
                Array(copy.size) { copy[it].face.icon?.key }, positions))
        }
    }
}
