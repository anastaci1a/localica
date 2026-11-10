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
}
