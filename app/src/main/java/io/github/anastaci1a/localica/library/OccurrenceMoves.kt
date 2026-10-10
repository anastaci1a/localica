package io.github.anastaci1a.localica.library

/** The insertion anchor is in the remaining list; selected occurrences retain source order. */
object OccurrenceMoves {
    fun <T> before(items: List<T>, selected: Set<String>, beforeId: String?, id: (T) -> String): List<T> {
        val known = items.map(id).toHashSet()
        require(selected.isNotEmpty() && known.containsAll(selected))
        val moving = items.filter { id(it) in selected }
        val remaining = items.filterNot { id(it) in selected }
        val index = if (beforeId == null) remaining.size else remaining.indexOfFirst { id(it) == beforeId }.also { require(it >= 0) }
        return remaining.take(index) + moving + remaining.drop(index)
    }
}
