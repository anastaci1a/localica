package io.github.anastaci1a.localica.ui

/** Carry the header coordinate through shared visible rows after lazy layout recycles it. */
internal class CollectionScroll {
    data class Item(val key: Any, val index: Int, val top: Int, val height: Int)
    private var previous = emptyList<Item>()
    private var top = 0f
    private var headerHeight = 0

    /** A measured pinch frame owns this coordinate until its verified handoff. */
    fun presented(value: Float, items: List<Item>) {
        top = value
        previous = items
    }

    fun update(items: List<Item>, atStart: Boolean): Float {
        if (items.isEmpty()) return top
        val header = items.firstOrNull { it.key == "browser-notice" }
        if (header != null) {
            top = header.top.toFloat()
            headerHeight = header.height
        } else {
            val shared = items.firstNotNullOfOrNull { item -> previous.firstOrNull { it.key == item.key }?.let { item.top - it.top } }
            top = when {
                atStart -> 0f
                shared != null -> top + shared
                else -> { // A deliberate jump can skip every previously visible row.
                    val first = items.first()
                    first.top - headerHeight.toFloat() - (first.index - 1).coerceAtLeast(0) * first.height
                }
            }
        }
        previous = items
        return top
    }
}
