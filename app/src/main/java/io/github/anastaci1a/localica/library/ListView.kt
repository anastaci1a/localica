package io.github.anastaci1a.localica.library

/** A pure projection of [source], an immutable revision: queue edits replace `entries`, and results own their rows.
 * It keeps the List contract; owners compare its [revision] instead, without reading an element (#957). */
private class MappedList<S, T>(val source: List<S>, f: Any, private val at: (Int) -> T) : AbstractList<T>(), RandomAccess {
    val revision: Revision = Revision((source as? MappedList<*, *>)?.revision ?: source, f)
    override val size get() = source.size
    override fun get(index: Int) = at(index)
}
/** A view's source by reference (or the inner view's revision) and its projection. */
private class Revision(val source: Any?, val f: Any) {
    override fun equals(other: Any?) = other is Revision && (source === other.source || source is Revision && source == other.source) && f == other.f
    override fun hashCode(): Int = if (source is Revision) source.hashCode() else System.identityHashCode(source)
}
internal val List<*>.revision: Any get() = (this as? MappedList<*, *>)?.revision ?: this
internal fun Any?.identityKey(): Any = Revision(this, Unit)
internal val List<*>.ownerRevision: Any get() = (this as? MappedList<*, *>)?.revision ?: identityKey()
internal fun <S, T> List<S>.mapView(f: (S) -> T): List<T> = MappedList(this, f) { f(this[it]) }
internal fun <S, T> List<S>.mapIndexedView(f: (Int, S) -> T): List<T> = MappedList(this, f) { f(it, this[it]) }
internal val List<*>.viewSource: List<*>? get() = (this as? MappedList<*, *>)?.source
