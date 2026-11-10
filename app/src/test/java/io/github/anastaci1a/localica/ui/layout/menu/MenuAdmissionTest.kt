package io.github.anastaci1a.localica.ui.layout.menu

import io.github.anastaci1a.localica.ui.spec.MarkerTone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** #757 M0: admission is structural and total, runs on every declaration and rejects instead of repairing. */
class MenuAdmissionTest {
    @Test fun aPageWithEveryRowKindIsAccepted() {
        val page = accepted(everyKind())
        assertEquals("Main", page.content.pageKey)
        assertEquals(16, page.content.rows().size)
        assertEquals(RowContent::class.java.permittedSubclasses.size, page.content.rows().map { row ->
            RowContent::class.java.permittedSubclasses.single { it.isInstance(row) } }.toSet().size)
    }

    @Test fun keysAreUniqueInTheirScope() {
        assertEquals(listOf(AdmissionRule.DuplicateKey), rules(page(TestPage.Main) {
            section("a") { toggle(local("x", "One"), false, {}); toggle(local("x", "Two"), false, {}) }
        }))
        assertEquals(listOf(AdmissionRule.DuplicateKey), rules(page(TestPage.Main) {
            section("a") { toggle(local("x", "One"), false, {}) }
            section("a") { toggle(local("y", "Two"), false, {}) }
        }))
        // The same local id in two sections is two different rows.
        accepted(page(TestPage.Main) {
            section("a") { toggle(local("x", "One"), false, {}) }
            section("b") { toggle(local("x", "Two"), false, {}) }
        })
        assertEquals(listOf(AdmissionRule.DuplicateKey), rules(page(TestPage.Main) {
            section("a") { legend(local("l")) { entry("m", MarkerTone.Positive, "One", Value.Ready(1L)); entry("m", MarkerTone.Caution, "Two", Value.Ready(2L)) } }
        }))
        assertEquals(listOf(AdmissionRule.DuplicateKey), rules(page(TestPage.Main) {
            section("a") { choice(local("c", "Choice"), listOf(Option("a", "A"), Option("a", "B")), "a", {}) }
        }))
    }

    @Test fun aSettingAppearsOncePerPageAcrossTabsAndCards() {
        val setting = TestSetting("Sync now")
        val violations = violations(page(TestPage.Main) {
            tabs(TestTab.One, {}) {
                tab(TestTab.One, "One") { section("a") { action(setting, TestIcon.Refresh) {} } }
                tab(TestTab.Two, "Two") { section("b") { statusCard(local("card")) { header("Card"); actions { action(setting, TestIcon.Refresh) {} } } } }
            }
        })
        assertEquals(listOf(AdmissionRule.DuplicateSetting), violations.map { it.rule })
        assertEquals("Main/Two/b/local:card/setting:Sync now", violations.single().key)
    }

    @Test fun tabCountIsTwoToFour() {
        fun tabs(count: Int) = page(TestPage.Main) {
            tabs(TestTab.One, {}) { TestTab.entries.take(count).forEach { tab(it, it.name) { section("s") { toggle(local("t", "T"), false, {}) } } } }
        }
        assertEquals(listOf(AdmissionRule.TabCount), rules(tabs(1)))
        assertEquals(listOf(AdmissionRule.TabCount), rules(tabs(5)))
        for (count in 2..4) accepted(tabs(count))
        assertEquals(listOf(AdmissionRule.UnknownSelection), rules(page(TestPage.Main) {
            tabs(TestTab.Five, {}) { tab(TestTab.One, "One") {}; tab(TestTab.Two, "Two") {} }
        }))
    }

    @Test fun aCardHasAtMostTwoActionsAndTwoHeaderLinesButAnyNumberOfStats() {
        assertEquals(listOf(AdmissionRule.HeaderInfoCount, AdmissionRule.CardActionCount), rules(page(TestPage.Main) {
            section("a") { statusCard(local("card")) {
                header("Card") { info(TestInfo.Songs, Value.Loading()); info(TestInfo.Artists, Value.Loading()); info(TestInfo.Albums, Value.Loading()) }
                actions { listOf("One", "Two", "Three").forEach { action(TestSetting(it), TestIcon.Refresh) {} } }
            } }
        }))
        val many = accepted(page(TestPage.Main) {
            section("a") { statusCard(local("card")) { header("Card"); stats { repeat(40) { index ->
                stat(object : InfoKey { override val key = "k$index"; override val data = setOf<Datum>(object : Datum { override val key = "d$index" }) }, "Stat $index", Value.Ready("$index")) } } } }
        })
        assertEquals(40, many.content.row<StatusCardContent>().stats.size)
    }

    @Test fun theSameDatumAppearsOncePerPanelEvenUnderDifferentKeys() {
        // The census's duplication: a header line "songs, artists, albums" next to the three stats.
        val violations = violations(page(TestPage.Main) {
            section("a") { statusCard(local("card")) {
                header("Card") { info(TestInfo.LibraryTotals, Value.Ready("1, 2, 3")) }
                stats { stat(TestInfo.Songs, "Songs", Value.Ready("1")); stat(TestInfo.Scrobbles, "Scrobbles", Value.Ready("9")) }
            } }
        })
        assertEquals(listOf(AdmissionRule.OverlappingDatum), violations.map { it.rule })
        assertEquals("Main/a/local:card/info:Songs", violations.single().key)
        assertEquals(listOf(AdmissionRule.DuplicateKey, AdmissionRule.DuplicateInfo, AdmissionRule.OverlappingDatum), rules(page(TestPage.Main) {
            section("a") { info(TestInfo.Songs, "Songs", Value.Ready("1")); info(TestInfo.Songs, "Songs again", Value.Ready("1")) }
        }))
        // Different panels (tabs) may each show the datum.
        accepted(page(TestPage.Main) {
            tabs(TestTab.One, {}) {
                tab(TestTab.One, "One") { section("a") { info(TestInfo.Songs, "Songs", Value.Ready("1")) } }
                tab(TestTab.Two, "Two") { section("a") { info(TestInfo.Songs, "Songs", Value.Ready("1")) } }
            }
        })
    }

    @Test fun requiredTextIsNotBlankAndAPopupIsAdmittedWithItsOwner() {
        assertEquals(listOf(AdmissionRule.BlankText), rules(page(TestPage.Main) { section("a") { toggle(local("x"), false, {}) } }))
        assertEquals(listOf(AdmissionRule.BlankText), rules(page(TestPage.Main) { section("a") { toggle(local("x", "T"), false, {}, helper = " ") } }))
        val violations = violations(page(TestPage.Main) {
            section("a") { action(local("x", "Delete"), TestIcon.Trash, opens = confirm("Delete?", "", ConfirmAction("Delete") {})) }
        })
        assertEquals(listOf("Main: BlankText at Main/a/local:x/popup"), violations.map { it.toString() })
    }

    @Test fun selectionsAndNumbersMustBeValid() {
        assertEquals(listOf(AdmissionRule.UnknownSelection), rules(page(TestPage.Main) {
            section("a") { choice(local("c", "Choice"), listOf(Option("a", "A"), Option("b", "B")), "z", {}) }
        }))
        assertEquals(listOf(AdmissionRule.InvalidNumber), rules(page(TestPage.Main) { section("a") { slider(local("s", "Slider"), 2f, {}, "200%") } }))
        assertEquals(listOf(AdmissionRule.InvalidNumber), rules(page(TestPage.Main) { section("a") { slider(local("s", "Slider"), Float.NaN, {}, "?") } }))
        assertEquals(listOf(AdmissionRule.InvalidNumber), rules(page(TestPage.Main) { section("a") { progress("p", 1.5f, "Working") } }))
    }

    @Test fun everyViolationIsReportedAndDiagnosticsCarryNoTitlesOrValues() {
        val violations = violations(page(TestPage.Main) {
            section("a") {
                toggle(local("x", "Secret title"), false, {}, helper = " ")
                field(local("x", "Other secret"), "typed form text", {})
                slider(local("s", "Slider"), 9f, {}, "secret value")
            }
        })
        assertEquals(listOf(AdmissionRule.BlankText, AdmissionRule.DuplicateKey, AdmissionRule.InvalidNumber), violations.map { it.rule })
        val text = violations.joinToString()
        for (secret in listOf("Secret title", "Other secret", "typed form text", "secret value")) assertFalse(text, secret in text)
        assertTrue(violations.all { it.page == "Main" })
    }

    @Test fun aRejectedDraftYieldsNoContent() {
        val result = AcceptedPage.admit(page(TestPage.Main) { section("a") { toggle(local("x"), false, {}) } })
        assertTrue(result is Admission.Rejected)
        assertEquals(listOf("pageKey", "violations"), result.javaClass.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted())
    }

    @Test fun equalContentReusesTheAcceptedInstanceAndVerdictButNeverTheBindings() {
        val cache = AdmissionCache()
        val first = (cache.admit(everyKind(onToggle = {})) as Admission.Accepted).page
        val second = (cache.admit(everyKind(onToggle = {})) as Admission.Accepted).page
        assertSame(first.content, second.content)
        assertSame(first.index, second.index)
        assertNotSame(first.bindings, second.bindings)
        assertEquals(1, cache.admissions)
        val changed = (cache.admit(everyKind(toggled = true)) as Admission.Accepted).page
        assertNotSame(first.content, changed.content)
        assertEquals(2, cache.admissions)
        // A rejected verdict is reused the same way, and an equal draft is never re-admitted into something else.
        val bad = { page(TestPage.Main) { section("a") { toggle(local("x"), false, {}) } } }
        assertSame(cache.admit(bad()), cache.admit(bad()))
        assertEquals(3, cache.admissions)
    }

    @Test fun recordIdsAreProvedUniqueOncePerSourceVersionBeforeAnyItemConsumesThem() = runBlocking {
        // Two equal ids much farther apart than one window. The first entry is the saved scroll anchor of the page that is showing.
        val showing = snapshot(50_000, version = 1)
        val flat = flatten(accepted(everyKind(showing)).content.panel())
        val anchor = flat.indexOf("Main/data/local:records/item:id-0")
        assertTrue(anchor >= 0)
        val rejected = RecordSnapshot.validate(GeneratedRecords(50_000, version = 2, duplicateAt = 40_000)) as RecordSnapshotResult.Rejected
        assertEquals(1, rejected.duplicateIds)
        assertEquals(2L, rejected.version)
        // No snapshot of version 2 exists, so nothing can declare it: the page keeps version 1 and its anchor.
        assertEquals(anchor, flatten(accepted(everyKind(showing)).content.panel()).indexOf("Main/data/local:records/item:id-0"))
        assertTrue(RecordSnapshot.validate(GeneratedRecords(50_000, version = 2)) is RecordSnapshotResult.Accepted)
        assertTrue(validated(GeneratedRecords(1, version = 3, blankAt = 0)) is RecordSnapshotResult.Rejected)
        assertTrue(validated(GeneratedRecords(1, sourceId = " ")) is RecordSnapshotResult.Rejected)
    }

    @Test fun aRecordRowIsEqualByCertificateSoARebuildNeverScansTheList() {
        val cache = AdmissionCache()
        val big = snapshot(50_000)
        val first = (cache.admit(everyKind(big)) as Admission.Accepted).page
        val again = (cache.admit(everyKind(snapshot(50_000))) as Admission.Accepted).page
        assertSame(first.content, again.content)
        assertEquals(1, cache.admissions)
        assertNotSame(first.content, (cache.admit(everyKind(snapshot(50_000, version = 2))) as Admission.Accepted).page.content)
        assertEquals(big.certificate, snapshot(50_000).certificate)
    }
}
