package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.runtime.Immutable
import io.github.anastaci1a.localica.ui.spec.MenuGap

/** What one item of the page stream is. Every heading, row, record entry, ordered entry and caption is its own item. */
enum class FlatKind { Heading, Row, RecordEntry, RecordEmpty, OrderedEntry, OrderedAdd, Caption }

@Immutable class FlatItem internal constructor(val kind: FlatKind, val section: SectionContent, val row: RowContent?, val entry: Int) {
    /** Stable across filtering and reordering: the qualified key of the row or entry. */
    val key: String get() = when (kind) {
        FlatKind.Heading -> "${section.key.path}#heading"
        FlatKind.Caption -> "${section.key.path}#caption"
        FlatKind.Row -> row!!.key.path
        FlatKind.RecordEntry -> "${row!!.key.path}/item:${(row as RecordsContent).snapshot.id(entry)}"
        FlatKind.RecordEmpty -> "${row!!.key.path}#empty"
        FlatKind.OrderedEntry -> (row as OrderedListContent).items[entry].key.path
        FlatKind.OrderedAdd -> "${row!!.key.path}#add"
    }
    /** Items of one type share lazy-list slots. */
    val contentType: Any get() = if (kind == FlatKind.Row) row!!.javaClass else kind
    override fun equals(other: Any?) = other is FlatItem && kind == other.kind && entry == other.entry && row == other.row && section.key == other.section.key &&
        (kind != FlatKind.Heading || section.heading == other.section.heading) && (kind != FlatKind.Caption || section.caption == other.section.caption)
    override fun hashCode() = 31 * (31 * kind.hashCode() + entry) + section.key.hashCode()
}

/**
 * The flat index of one panel, computed from content without composing anything. Fixed items are counted and a
 * record source contributes one `(start, count)` span, so building and reading it costs by sections and rows,
 * never by records.
 */
@Immutable class FlatPage internal constructor(private val starts: IntArray, private val kinds: Array<FlatKind>,
    private val sections: Array<SectionContent>, private val rows: Array<RowContent?>, val count: Int, private val afterTabs: Boolean) {

    private fun span(index: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) { val mid = (low + high + 1) ushr 1; if (starts[mid] <= index) low = mid else high = mid - 1 }
        return low
    }

    fun item(index: Int): FlatItem {
        require(index in 0 until count)
        val span = span(index)
        return FlatItem(kinds[span], sections[span], rows[span], index - starts[span])
    }

    /** The one gap before an item. Rows and headings have no outer margins of their own. */
    fun gapBefore(index: Int): MenuGap {
        if (index == 0) return gapBefore(null, null, kinds[0], sections[0].key, afterTabs)
        val previous = span(index - 1)
        val next = span(index)
        return gapBefore(kinds[previous], sections[previous].key, kinds[next], sections[next].key, afterTabs)
    }

    /** Flat index of a qualified row or entry key, or -1. Used by deep links before anything is composed. */
    fun indexOf(key: String): Int {
        for (span in starts.indices) {
            val row = rows[span] ?: continue
            val path = row.key.path
            if (key == path && kinds[span] != FlatKind.OrderedAdd) return starts[span]
            if (!key.startsWith(path) || !key.startsWith("/item:", path.length)) continue
            val id = key.substring(path.length + 6)
            val offset = when (row) {
                is RecordsContent -> if (kinds[span] == FlatKind.RecordEntry) row.snapshot.indexOf(id) else -1
                is OrderedListContent -> if (kinds[span] == FlatKind.OrderedEntry) row.items.indexOfFirst { it.id == id } else -1
                else -> -1
            }
            if (offset >= 0) return starts[span] + offset
        }
        return -1
    }
}

/** DESIGN 5.3. Exactly one gap between two neighbours; [previous] null means the first item of the stream. */
fun gapBefore(previous: FlatKind?, previousSection: QualifiedKey?, next: FlatKind, nextSection: QualifiedKey, afterTabs: Boolean): MenuGap = when {
    previous == null -> if (afterTabs) MenuGap.TabsToFirst else MenuGap.PageTop
    previousSection != nextSection -> MenuGap.SectionToSection
    previous == FlatKind.Heading -> MenuGap.HeadingToRow
    next == FlatKind.Caption -> MenuGap.RowToCaption
    else -> MenuGap.RowToRow
}

/** A section with no rows emits nothing; an empty record source emits its required `empty` text as one item. */
fun flatten(sections: List<SectionContent>, afterTabs: Boolean = false): FlatPage {
    val starts = ArrayList<Int>()
    val kinds = ArrayList<FlatKind>()
    val owners = ArrayList<SectionContent>()
    val rows = ArrayList<RowContent?>()
    var count = 0
    fun span(kind: FlatKind, section: SectionContent, row: RowContent?, size: Int) {
        if (size <= 0) return
        starts += count; kinds += kind; owners += section; rows += row; count += size
    }
    for (section in sections) {
        // A heading is always followed by a row of its own section: sections whose rows emit no item are skipped whole.
        if (section.rows.none { it !is OrderedListContent || it.items.isNotEmpty() || it.addLabel != null }) continue
        if (section.heading != null) span(FlatKind.Heading, section, null, 1)
        for (row in section.rows) when (row) {
            is RecordsContent -> if (row.snapshot.size == 0) span(FlatKind.RecordEmpty, section, row, 1) else span(FlatKind.RecordEntry, section, row, row.snapshot.size)
            is OrderedListContent -> {
                span(FlatKind.OrderedEntry, section, row, row.items.size)
                if (row.addLabel != null) span(FlatKind.OrderedAdd, section, row, 1)
            }
            else -> span(FlatKind.Row, section, row, 1)
        }
        if (section.caption != null) span(FlatKind.Caption, section, null, 1)
    }
    return FlatPage(starts.toIntArray(), kinds.toTypedArray(), owners.toTypedArray(), rows.toTypedArray(), count, afterTabs)
}

/** The sections of the visible panel: the selected tab, or the page body. */
fun PageContent.panel(): List<SectionContent> = when (val body = body) {
    is SectionsBody -> body.sections
    is TabsBody -> body.tabs.firstOrNull { it.id == body.selected }?.sections.orEmpty()
}
