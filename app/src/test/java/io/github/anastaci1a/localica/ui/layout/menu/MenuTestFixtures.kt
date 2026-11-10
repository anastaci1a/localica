package io.github.anastaci1a.localica.ui.layout.menu

import io.github.anastaci1a.localica.ui.spec.MarkerTone

internal enum class TestPage(override val title: String, override val summary: String? = null, override val icon: MenuIcon? = null) : PageRef {
    Main("Main page"), Child("Child page", "Where it leads", TestIcon.Folder);
    override val key get() = name
}
internal enum class TestIcon : MenuIcon { Folder, Refresh, Trash; override val key get() = "test.$name" }
internal enum class TestTab : TabId { One, Two, Three, Four, Five; override val key get() = name }
internal enum class TestDatum : Datum { Songs, Artists, Albums, Scrobbles, LastSync; override val key get() = name }
internal enum class TestInfo(override val data: Set<Datum>) : InfoKey {
    Songs(setOf(TestDatum.Songs)), Artists(setOf(TestDatum.Artists)), Albums(setOf(TestDatum.Albums)), Scrobbles(setOf(TestDatum.Scrobbles)),
    LastSync(setOf(TestDatum.LastSync)), LibraryTotals(setOf(TestDatum.Songs, TestDatum.Artists, TestDatum.Albums));
    override val key get() = name
}
internal class TestSetting(override val id: String, override val title: String = id) : SettingKey

internal fun accepted(draft: PageDraft): AcceptedPage = (AcceptedPage.admit(draft) as Admission.Accepted).page
internal fun violations(draft: PageDraft): List<Violation> = (AcceptedPage.admit(draft) as Admission.Rejected).violations
internal fun rules(draft: PageDraft): List<AdmissionRule> = violations(draft).map { it.rule }

internal fun PageContent.sections(): List<SectionContent> = when (val body = body) {
    is SectionsBody -> body.sections
    is TabsBody -> body.tabs.flatMap { it.sections }
}
internal fun PageContent.rows(): List<RowContent> = sections().flatMap { it.rows }
internal inline fun <reified T : RowContent> PageContent.row(): T = rows().filterIsInstance<T>().single()

/** A computed data-layer stand-in that owns no per-entry memory; [duplicateAt] repeats id-0 at that position. */
internal class GeneratedRecords(override val size: Int, override val sourceId: String = "records", override val version: Long = 1,
    private val duplicateAt: Int = -1, private val blankAt: Int = -1) : RecordSource {
    override fun id(index: Int) = if (index == duplicateAt) "id-0" else if (index == blankAt) " " else "id-$index"
    override fun title(index: Int) = "Record $index"
    override fun subtitle(index: Int) = "Detail $index"
    override fun indexOf(id: String) = id.removePrefix("id-").toIntOrNull()?.takeIf { it in 0 until size } ?: -1
}

internal fun validated(source: RecordSource): RecordSnapshotResult = RecordSnapshot.validateBlocking(source)

internal fun snapshot(size: Int, source: String = "records", version: Long = 1): RecordSnapshot =
    (validated(GeneratedRecords(size, source, version)) as RecordSnapshotResult.Accepted).snapshot

/** A page touching every row kind, used by the boundary, flow and dispatch tests. */
internal fun everyKind(records: RecordSnapshot = snapshot(3), toggled: Boolean = false, onToggle: (Boolean) -> Unit = {},
    onRecord: (String) -> Unit = {}, onConfirm: () -> Unit = {}, onRename: (String) -> Unit = {}): PageDraft = page(TestPage.Main) {
    section("links", heading = "Links", caption = "A caption for the links") {
        nav(TestPage.Child)
        link(local("plain", "Plain link"), summary = "With a summary", leading = Lead.Marker(MarkerTone.Positive)) {}
        value(local("limit", "Maximum queues"), "Unlimited", opens = textEntry("Maximum queues", "Limit", "0", onRename))
        action(TestSetting("Delete data"), TestIcon.Trash, role = ActionRole.Destructive,
            opens = confirm("Delete data?", "This cannot be undone.", ConfirmAction("Delete", TestIcon.Trash, ActionRole.Destructive, onConfirm)))
    }
    section("controls", heading = "Controls") {
        toggle(TestSetting("Toggle"), toggled, onToggle, helper = "A helper")
        choice(TestSetting("Choice"), listOf(Option("a", "First"), Option("b", "Second")), "a", {})
        slider(TestSetting("Slider"), .4f, {}, "40%", low = "Low", high = "High")
        field(TestSetting("Field"), "text", {})
        info(TestInfo.LastSync, "Last sync", Value.Ready("Today"))
        progress("progress", .5f, "Half way")
        notice("notice", NoticeTone.Warning, "Online features are off.", RowAction("Enable", TestIcon.Refresh) {})
        preview(object : PreviewId { override val key = "gray" })
    }
    section("data") {
        statusCard(local("card")) {
            header("Archive", progress = .25f) { info(TestInfo.Scrobbles, Value.Loading()) }
            stats { stat(TestInfo.Songs, "Songs", Value.Ready("12")); stat(TestInfo.Artists, "Artists", Value.Loading()) }
            actions { action(TestSetting("Sync now"), TestIcon.Refresh) {} }
        }
        legend(local("legend"), bar = true) {
            entry("matched", MarkerTone.Positive, "Matched", Value.Ready(10L)) {}
            entry("review", MarkerTone.Negative, "Needs review", Value.Loading())
        }
        orderedList(local("order"), listOf(OrderedItem("x", "First"), OrderedItem("y", "Second")), { _, _ -> }, {}, RowAction("Add", TestIcon.Folder) {})
        records(local("records"), records, "Nothing here", onRecord)
    }
}
