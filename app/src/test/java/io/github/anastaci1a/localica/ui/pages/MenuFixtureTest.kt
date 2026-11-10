package io.github.anastaci1a.localica.ui.pages

import io.github.anastaci1a.localica.ui.layout.menu.*
import org.junit.Assert.*
import org.junit.Test

/**
 * #757: every registered page is declared for every fixture and must be admitted, on the JVM, with no device.
 * Complementary evidence only: admission itself runs on every real declaration at run time.
 */
class MenuFixtureTest {
    private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private fun panels(content: PageContent): List<List<SectionContent>> = when (val body = content.body) {
        is SectionsBody -> listOf(body.sections)
        is TabsBody -> body.tabs.map { it.sections }
    }
    private fun supporting(row: RowContent): List<String> = when (row) {
        is NavContent -> listOfNotNull(row.summary)
        is LinkContent -> listOfNotNull(row.summary)
        is ChoiceContent -> listOfNotNull(row.helper)
        is ToggleContent -> listOfNotNull(row.helper)
        is SliderContent -> listOfNotNull(row.helper)
        else -> emptyList()
    }
    private fun labelled(row: RowContent): List<String> = when (row) {
        is ValueContent -> listOf("${row.title}|${row.value}")
        is InfoContent -> listOfNotNull((row.value as? Value.Ready)?.let { "${row.title}|${it.value}" })
        is StatusCardContent -> row.stats.mapNotNull { stat -> (stat.value as? Value.Ready)?.let { "${stat.label}|${it.value}" } }
        else -> emptyList()
    }

    @Test fun registryIsNotEmptyAndEveryPageHasFixtures() {
        assertTrue(MenuPages.sources.isNotEmpty())
        assertEquals(MenuPages.sources.size, MenuPages.sources.map { it.id }.toSet().size)
        MenuPages.sources.forEach { assertTrue("${it.id} has no fixtures", it.fixtures.isNotEmpty()) }
    }

    @Test fun everyPageIsAdmittedForEveryFixture() {
        for (source in MenuPages.sources) for ((name, draft) in source.drafts()) {
            val result = AcceptedPage.admit(draft)
            assertTrue("${source.id}/$name: ${(result as? Admission.Rejected)?.violations}", result is Admission.Accepted)
            assertEquals(source.id.key, result.pageKey)
        }
    }

    @Test fun captionsDoNotRestateRowsAndNoLabelledValueRepeatsInAPanel() {
        for (source in MenuPages.sources) for ((name, draft) in source.drafts()) {
            val content = (AcceptedPage.admit(draft) as Admission.Accepted).page.content
            for (panel in panels(content)) {
                for (section in panel) {
                    val caption = section.caption?.let(::normalized) ?: continue
                    val restated = section.rows.flatMap(::supporting).filter { normalized(it) == caption }
                    assertEquals("${source.id}/$name ${section.key}: the caption restates a row", emptyList<String>(), restated)
                }
                val pairs = panel.flatMap { it.rows }.flatMap(::labelled).map(::normalized)
                assertEquals("${source.id}/$name: a label and value repeat in one panel", pairs.size, pairs.toSet().size)
            }
        }
    }

    @Test fun theSpecimenCoversEveryRowKindAndPopupKind() {
        val contents = SpecimenSource.drafts().map { (AcceptedPage.admit(it.second) as Admission.Accepted).page.content }
        val rows = contents.flatMap { content -> panels(content).flatten().flatMap { it.rows } }
        for (kind in RowContent::class.java.permittedSubclasses) assertTrue("no specimen row of ${kind.simpleName}", rows.any { kind.isInstance(it) })
        val popups = rows.mapNotNull { it.opens }
        for (kind in PopupContent::class.java.permittedSubclasses) assertTrue("no specimen popup of ${kind.simpleName}", popups.any { kind.isInstance(it) })
    }

    @Test fun declaringIsPureEqualStateGivesEqualContent() {
        for (fixture in SpecimenFixtures) assertEquals(fixture.name, (AcceptedPage.admit(specimenPage(fixture.state)) as Admission.Accepted).page.content,
            (AcceptedPage.admit(specimenPage(fixture.state)) as Admission.Accepted).page.content)
    }
}
