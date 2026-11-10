package io.github.anastaci1a.localica.ui.layout.menu

import io.github.anastaci1a.localica.ui.spec.MenuGap
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #757 M0: the page stream is flattened from content, with one scaffold-owned gap between neighbours (DESIGN 3.6, 5.3). */
class MenuFlowTest {
    private fun flat(draft: PageDraft) = flatten(accepted(draft).content.panel())
    private fun FlatPage.kinds() = List(count) { item(it).kind }
    private fun FlatPage.gaps() = List(count) { gapBefore(it) }

    @Test fun everyHeadingRowEntryAndCaptionIsItsOwnItemInOrder() {
        val flat = flat(everyKind())
        assertEquals(listOf(FlatKind.Heading, FlatKind.Row, FlatKind.Row, FlatKind.Row, FlatKind.Row, FlatKind.Caption,
            FlatKind.Heading) + List(8) { FlatKind.Row } + listOf(FlatKind.Row, FlatKind.Row, FlatKind.OrderedEntry, FlatKind.OrderedEntry, FlatKind.OrderedAdd,
            FlatKind.RecordEntry, FlatKind.RecordEntry, FlatKind.RecordEntry), flat.kinds())
        assertEquals(List(flat.count) { flat.item(it).key }.toSet().size, flat.count)
    }

    @Test fun exactlyOneGapSeparatesNeighboursFollowingTheAdjacencyTable() {
        assertEquals(listOf(MenuGap.PageTop, MenuGap.HeadingToRow, MenuGap.RowToRow, MenuGap.RowToRow, MenuGap.RowToRow, MenuGap.RowToCaption,
            MenuGap.SectionToSection, MenuGap.HeadingToRow) + List(7) { MenuGap.RowToRow } + listOf(MenuGap.SectionToSection) + List(7) { MenuGap.RowToRow },
            flat(everyKind()).gaps())
        assertEquals(MenuGap.TabsToFirst, gapBefore(null, null, FlatKind.Row, QualifiedKey("p/s"), afterTabs = true))
        // A section without a heading is still a section: 24 dp, not a row gap.
        assertEquals(MenuGap.SectionToSection, gapBefore(FlatKind.Row, QualifiedKey("p/a"), FlatKind.Row, QualifiedKey("p/b"), afterTabs = false))
        assertEquals(MenuGap.SectionToSection, gapBefore(FlatKind.Caption, QualifiedKey("p/a"), FlatKind.Heading, QualifiedKey("p/b"), afterTabs = false))
    }

    @Test fun edgeCasesAreDefined() {
        val flat = flat(page(TestPage.Main) {
            section("empty", heading = "Never shown", caption = "Never shown") {}
            section("only-empty-list", heading = "Never shown") { orderedList(local("o"), emptyList(), { _, _ -> }) }
            section("records", heading = "Records") { records(local("r"), snapshot(0), "No records yet") }
        })
        assertEquals(listOf(FlatKind.Heading, FlatKind.RecordEmpty), flat.kinds())
        assertEquals("Main/records/local:r#empty", flat.item(1).key)
        assertEquals(0, flat(page(TestPage.Main) {}).count)
    }

    @Test fun theSelectedTabIsThePanel() {
        val content = accepted(page(TestPage.Main) {
            tabs(TestTab.Two, {}) {
                tab(TestTab.One, "One") { section("a") { toggle(local("x", "X"), false, {}) } }
                tab(TestTab.Two, "Two") { section("b") { toggle(local("y", "Y"), false, {}); toggle(local("z", "Z"), false, {}) } }
            }
        }).content
        val flat = flatten(content.panel(), afterTabs = true)
        assertEquals(listOf("Main/Two/b/local:y", "Main/Two/b/local:z"), List(flat.count) { flat.item(it).key })
        assertEquals(listOf(MenuGap.TabsToFirst, MenuGap.RowToRow), flat.gaps())
    }

    @Test fun deepLinksResolveAFlatIndexFromContentWithoutComposing() {
        val flat = flat(everyKind(snapshot(50_000)))
        assertEquals(8, flat.indexOf("Main/controls/setting:Choice"))
        val first = flat.indexOf("Main/data/local:records/item:id-0")
        assertEquals(first + 49_999, flat.indexOf("Main/data/local:records/item:id-49999"))
        assertEquals("Main/data/local:records/item:id-31337", flat.item(first + 31_337).key)
        assertEquals(-1, flat.indexOf("Main/data/local:records/item:missing"))
        assertEquals(-1, flat.indexOf("Main/nowhere"))
    }

    /**
     * Operator scale (HARD RULE 5): declaring, admitting and flattening a page must not grow with record count.
     * Counts are compared exactly; time and heap are recorded as evidence, not asserted against a device budget.
     */
    @Test fun declarationAdmissionAndFlatteningDoNotGrowWithRecordCount() {
        val sizes = listOf(0, 13_400, 50_000)
        val snapshots = sizes.map { snapshot(it) }
        val runtime = Runtime.getRuntime()
        // Unit tests compile against android.jar, which has no java.lang.management; the test JVM does.
        val beans = Class.forName("java.lang.management.ManagementFactory").getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        fun gcCount() = beans.sumOf { bean -> (bean!!.javaClass.interfaces.first { it.name.endsWith("GarbageCollectorMXBean") }.getMethod("getCollectionCount").invoke(bean) as Long).coerceAtLeast(0) }
        fun measure(records: RecordSnapshot): Triple<Double, Long, Long> {
            repeat(2_000) { flatten((AcceptedPage.admit(everyKind(records)) as Admission.Accepted).page.content.panel()) }
            System.gc()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val gcBefore = gcCount()
            val start = System.nanoTime()
            val rounds = 5_000
            var spans = 0
            repeat(rounds) { spans += flatten((AcceptedPage.admit(everyKind(records)) as Admission.Accepted).page.content.panel()).count.coerceAtMost(1) }
            val micros = (System.nanoTime() - start) / 1_000.0 / rounds
            assertEquals(rounds, spans)
            return Triple(micros, runtime.totalMemory() - runtime.freeMemory() - heapBefore, gcCount() - gcBefore)
        }
        val results = snapshots.map(::measure)
        val report = buildString {
            appendLine("records,declare+admit+flatten_us,heap_delta_bytes,gc_count")
            sizes.zip(results).forEach { (size, r) -> appendLine("$size,${"%.2f".format(r.first)},${r.second},${r.third}") }
            appendLine("# JVM unit test on the build host; not a qualified device timing (DESIGN 12 budgets are measured at M1).")
        }
        File("build/reports/menu").apply { mkdirs() }.resolve("declaration-scale.csv").writeText(report)
        println(report)
        // Same number of spans and the same work whatever the record count: allow generous noise, fail on linear growth.
        val base = results.first().first
        assertTrue("declaration cost grew with record count: $report", results.all { it.first < base * 4 + 50 })
    }

    /** Canonical data as the data layer would own it: one id array, read in place. */
    private class OwnedRecords(override val size: Int) : RecordSource {
        private val ids = Array(size) { "id-$it" }
        override val sourceId get() = "owned"
        override val version get() = 1L
        override fun id(index: Int) = ids[index]
        override fun title(index: Int) = ids[index]
        override fun subtitle(index: Int): String? = ids[index]
        // Allocation-free, like the hash lookup a real owner already has, so the columns below show menu cost only.
        override fun indexOf(id: String): Int {
            if (!id.startsWith("id-") || id.length == 3) return -1
            var value = 0
            for (at in 3 until id.length) { val digit = id[at] - '0'; if (digit !in 0..9) return -1; value = value * 10 + digit }
            return if (value < size && ids[value] == id) value else -1
        }
    }

    /**
     * Operator scale (HARD RULE 5, agent-19 M0 ruling 3): the whole snapshot lifecycle. The dataset is built and
     * measured first; what the menu then adds (create, page entry, scrolling, replacement, disposal) is reported
     * as allocated bytes, retained heap and GC count, and must not grow with the dataset.
     */
    @Test fun theRecordSnapshotLifecycleAddsNoMemoryThatGrowsWithTheDataset() {
        val runtime = Runtime.getRuntime()
        val management = Class.forName("java.lang.management.ManagementFactory")
        val beans = management.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        fun gcCount() = beans.sumOf { bean -> (bean!!.javaClass.interfaces.first { it.name.endsWith("GarbageCollectorMXBean") }.getMethod("getCollectionCount").invoke(bean) as Long).coerceAtLeast(0) }
        val threads = management.getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun allocated() = allocatedBytes.invoke(threads, Thread.currentThread().id) as Long
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(20) }; return runtime.totalMemory() - runtime.freeMemory() }
        class Row(val size: Int, val dataset: Long, val create: Long, val entry: Long, val scroll: Long, val replace: Long, val retained: Long, val disposed: Long, val gc: Long)
        fun lifecycle(size: Int): Row {
            val empty = used()
            val source = OwnedRecords(size)
            val owned = used()
            val gcBefore = gcCount()
            var mark = allocated()
            var snapshot: RecordSnapshot? = (RecordSnapshot.validateBlocking(source) as RecordSnapshotResult.Accepted).snapshot
            val create = allocated() - mark; mark = allocated()
            var flat: FlatPage? = flatten(accepted(everyKind(snapshot!!)).content.panel())
            val entry = allocated() - mark; mark = allocated()
            // 200 windows of 30 visible items spread over the whole list, plus a deep link per window.
            var read = 0
            val first = flat!!.indexOf("Main/data/local:records/item:id-0")
            if (size > 0) repeat(200) { window ->
                val start = (size.toLong() * window / 200).toInt()
                for (index in start until minOf(size, start + 30)) {
                    read += flat!!.item(first + index).key.length + snapshot!!.title(index).length + (snapshot!!.subtitle(index)?.length ?: 0)
                }
                assertEquals(first + start, flat!!.indexOf("Main/data/local:records/item:id-$start"))
            }
            val scroll = allocated() - mark; mark = allocated()
            assertTrue(size == 0 || read > 0)
            snapshot = (RecordSnapshot.validateBlocking(source) as RecordSnapshotResult.Accepted).snapshot
            flat = flatten(accepted(everyKind(snapshot)).content.panel())
            val replace = allocated() - mark
            val gcs = gcCount() - gcBefore
            val retained = used() - owned
            assertEquals(size, snapshot.size); assertTrue(flat.count > 0)
            snapshot = null; flat = null
            val disposed = used() - owned
            assertEquals(size, source.size)
            return Row(size, owned - empty, create, entry, scroll, replace, retained, disposed, gcs)
        }
        lifecycle(1_000) // warm-up, so class loading is not charged to the first size
        val rows = listOf(0, 13_400, 50_000, 1_000_000).map(::lifecycle)
        val report = buildString {
            appendLine("records,dataset_heap_bytes,create_alloc_bytes,entry_alloc_bytes,scroll_alloc_bytes,replace_alloc_bytes,retained_heap_delta_bytes,after_dispose_heap_delta_bytes,gc_count")
            rows.forEach { appendLine("${it.size},${it.dataset},${it.create},${it.entry},${it.scroll},${it.replace},${it.retained},${it.disposed},${it.gc}") }
            appendLine("# JVM unit test on the build host. Allocated bytes are exact per thread and bound the peak; heap deltas are after forced GC.")
            appendLine("# The dataset column is the data layer's own cost and is not menu memory. Not a device measurement.")
        }
        File("build/reports/menu").apply { mkdirs() }.resolve("record-lifecycle-scale.csv").writeText(report)
        println(report)
        val base = rows[1]
        for (row in rows.drop(1)) {
            assertTrue("creating a snapshot allocated with the list: $report", row.create < 4_096)
            assertTrue("page entry allocated with the list: $report", row.entry < base.entry * 2 + 16_384)
            assertTrue("scrolling allocated with the list: $report", row.scroll < base.scroll * 2 + 16_384)
            assertTrue("replacement allocated with the list: $report", row.replace < base.replace * 2 + 16_384)
            assertTrue("the menu retained memory with the list: $report", row.retained < 1_048_576)
        }
    }
}
