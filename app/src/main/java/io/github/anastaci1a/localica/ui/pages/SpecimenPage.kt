package io.github.anastaci1a.localica.ui.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.ui.AppIcon
import io.github.anastaci1a.localica.ui.layout.menu.*
import io.github.anastaci1a.localica.ui.spec.MarkerTone

/**
 * #757 specimen: every row kind in every combination (with and without helper, summary, leading, disabled,
 * loading, long text). Reachable in debug builds only, and a fixture of the invariant suite, so a new row kind
 * cannot ship without passing it.
 */
internal enum class SpecimenTab : TabId { Rows, Controls, Data; override val key: String get() = name }
internal enum class SpecimenDatum : Datum { Scrobbles, Songs, Artists, Albums, Share, LastSync, Version; override val key: String get() = name }
internal enum class SpecimenInfo(vararg data: Datum) : InfoKey {
    Scrobbles(SpecimenDatum.Scrobbles), Songs(SpecimenDatum.Songs), Artists(SpecimenDatum.Artists), Albums(SpecimenDatum.Albums),
    Share(SpecimenDatum.Share), LastSync(SpecimenDatum.LastSync), Version(SpecimenDatum.Version);
    override val data: Set<Datum> = data.toSet()
    override val key: String get() = name
}
internal enum class SpecimenPreview : PreviewId { Swatch; override val key: String get() = name }
private class SpecimenSetting(override val id: String, override val title: String = id) : SettingKey

internal class SpecimenActions(val tab: (SpecimenTab) -> Unit = {}, val toggle: (Boolean) -> Unit = {}, val choice: (String) -> Unit = {},
    val slider: (Float) -> Unit = {}, val text: (String) -> Unit = {}, val name: (String) -> Unit = {}, val move: (String, Int) -> Unit = { _, _ -> },
    val remove: (String) -> Unit = {}, val event: (String) -> Unit = {})

internal class SpecimenState(val tab: SpecimenTab = SpecimenTab.Rows, val toggle: Boolean = true, val choice: String = "list", val slider: Float = .4f,
    val text: String = "anastaci1a", val name: String = "Favourites", val loading: Boolean = false, val long: Boolean = false, val syncing: Boolean = false,
    val order: List<String> = listOf("Songs", "Albums", "Artists"), val records: RecordSnapshot = specimenRecords(3, false),
    val on: SpecimenActions = SpecimenActions())

internal fun specimenRecords(count: Int, long: Boolean): RecordSnapshot {
    val entries = LinkedHashMap<String, RecordFace>()
    repeat(count) { index ->
        entries["record-$index"] = RecordFace(if (long && index == 0) "Supercalifragilisticexpialidocious_unbreakable_file_name_without_any_spaces_at_all.flac" else "Record ${index + 1}",
            "Detail ${index + 1}", AppIcon.TabSongs)
    }
    return (RecordSnapshot.fromMap("specimen-records", (count * 2 + if (long) 1 else 0).toLong(), entries) as RecordSnapshotResult.Accepted).snapshot
}

private fun loadable(state: SpecimenState, value: String): Value<String> = if (state.loading) Value.Loading() else Value.Ready(value)

internal fun specimenPage(s: SpecimenState): PageDraft = page(PageId.Specimen) {
    val on = s.on
    val longTitle = "A deliberately long row title that has to wrap onto a second line at narrow widths"
    tabs(s.tab, on.tab) {
        tab(SpecimenTab.Rows, "Rows") {
            section("navigation", heading = "Navigation and links", caption = "Links open a page or a popup; the chevron and the icon stay centred on the row.") {
                nav(PageId.SpecimenChild)
                link(local("plain", "Plain link")) { on.event("plain") }
                link(local("summary", if (s.long) longTitle else "Link with summary"), summary = "One line of supporting text", leading = Lead.Icon(AppIcon.ActionInfo)) { on.event("summary") }
                link(local("marker", "Link with a marker"), summary = "Marker in the shared leading slot", leading = Lead.Marker(MarkerTone.Caution)) { on.event("marker") }
                link(local("disabled", "Disabled link"), enabled = false) { on.event("disabled") }
                link(local("message", "Link that opens a message"), opens = message("About this specimen", "Every row on this page is declared, not laid out."))
            }
            section("values", heading = "Values and actions") {
                value(local("name", "Playlist name"), s.name, opens = textEntry("Rename playlist", "Name", s.name, on.name))
                info(SpecimenInfo.Version, "Version", loadable(s, "0.33.19"))
                info(SpecimenInfo.LastSync, "Last sync", loadable(s, "Today, 9:41 AM"), icon = AppIcon.ActionRefresh) { on.event("last-sync") }
                action(SpecimenSetting("Rescan library"), AppIcon.ActionRescan) { on.event("rescan") }
                action(SpecimenSetting("Delete specimen data"), AppIcon.ActionTrash, role = ActionRole.Destructive,
                    opens = confirm("Delete specimen data?", "This deletes only the specimen's sample values.",
                        ConfirmAction("Delete", AppIcon.ActionTrash, ActionRole.Destructive) { on.event("delete") }))
                action(SpecimenSetting("Unavailable action"), AppIcon.ActionSave, enabled = false) { on.event("unavailable") }
            }
            section("notices", heading = "Notices and progress") {
                notice("info", NoticeTone.Info, "Online features are off.")
                notice("warning", NoticeTone.Warning, "The last sync did not finish.", RowAction("Sync now", AppIcon.ActionRefresh) { on.event("fix") })
                notice("error", NoticeTone.Error, "The archive could not be read.")
                progress("determinate", .35f, "Reading tags, 35%")
                progress("indeterminate", null, "Waiting for the scanner")
            }
        }
        tab(SpecimenTab.Controls, "Controls") {
            section("toggles", heading = "Switches") {
                toggle(SpecimenSetting("Specimen switch"), s.toggle, on.toggle)
                toggle(SpecimenSetting("Switch with helper"), s.toggle, on.toggle,
                    helper = "Helper text sits inside its row, under the title, at the title's start.")
                toggle(SpecimenSetting(if (s.long) longTitle else "Disabled switch"), false, on.toggle, enabled = false)
            }
            section("choices", heading = "Choices") {
                choice(SpecimenSetting("Layout"), listOf(Option("list", "List"), Option("grid", "Grid")), s.choice.takeIf { it != "carousel" } ?: "list", on.choice)
                choice(SpecimenSetting("Layout with helper"), listOf(Option("list", "List"), Option("grid", "Grid"), Option("carousel", "Carousel")), s.choice, on.choice,
                    helper = "Inline when every label fits on one line; otherwise the choice box.")
                choice(SpecimenSetting("Long choice"), listOf(Option("list", "Comfortable list"), Option("grid", "Compact gallery grid"),
                    Option("carousel", "Large artwork carousel")), s.choice, on.choice)
                choice(SpecimenSetting("Disabled choice"), listOf(Option("list", "List"), Option("grid", "Grid")), "list", on.choice, enabled = false)
            }
            section("sliders", heading = "Sliders and fields", caption = "A section caption follows the last row of its section.") {
                slider(SpecimenSetting("Interface gray"), s.slider, on.slider, "${(s.slider * 100).toInt()}%")
                slider(SpecimenSetting("Slider with labels"), s.slider, on.slider, "${(s.slider * 100).toInt()}%", low = "Darker", high = "Lighter",
                    helper = "The value sits at the end of the title line.")
                slider(SpecimenSetting("Disabled slider"), .5f, on.slider, "50%", enabled = false)
                field(SpecimenSetting("Username"), s.text, on.text)
                field(SpecimenSetting("Field with error"), s.text, on.text, error = "This name is not available.")
                preview(SpecimenPreview.Swatch)
            }
        }
        tab(SpecimenTab.Data, "Data") {
            section("archive") {
                statusCard(local("archive")) {
                    header(if (s.syncing) "Syncing…" else "Specimen archive", progress = if (s.syncing) .6f else null, icon = AppIcon.TabLibrary) {
                        info(SpecimenInfo.LastSync, loadable(s, "Last sync today, 9:41 AM"))
                    }
                    stats {
                        stat(SpecimenInfo.Scrobbles, "Scrobbles", loadable(s, if (s.long) "1,234,567,890" else "48,210"))
                        stat(SpecimenInfo.Songs, "Songs", loadable(s, "13,400"))
                        stat(SpecimenInfo.Artists, "Artists", loadable(s, "1,902"))
                        stat(SpecimenInfo.Albums, "Albums", loadable(s, "2,455"))
                        stat(SpecimenInfo.Share, "Matched share", loadable(s, "92%"))
                    }
                    actions {
                        if (s.syncing) action(SpecimenSetting("Stop sync"), AppIcon.ActionClose) { on.event("stop") }
                        else {
                            action(SpecimenSetting("Sync now"), AppIcon.ActionRefresh) { on.event("sync") }
                            action(SpecimenSetting("Full rescan"), AppIcon.ActionRescan, enabled = !s.loading) { on.event("full-rescan") }
                        }
                    }
                }
            }
            section("matching", heading = "Matching") {
                legend(local("buckets"), bar = true) {
                    entry("matched", MarkerTone.Positive, "Matched", if (s.loading) Value.Loading() else Value.Ready(41_200L)) { on.event("matched") }
                    entry("judgment", MarkerTone.Caution, "Judgment calls", if (s.loading) Value.Loading() else Value.Ready(3_100L)) { on.event("judgment") }
                    entry("review", MarkerTone.Negative, "Needs review", if (s.loading) Value.Loading() else Value.Ready(1_250L)) { on.event("review") }
                    entry("absent", MarkerTone.Neutral, "Not in your library", if (s.loading) Value.Loading() else Value.Ready(2_400L))
                    entry("editions", MarkerTone.Accent, "Auto-matched editions", if (s.loading) Value.Loading() else Value.Ready(260L)) { on.event("editions") }
                }
            }
            section("order", heading = "Ordered list") {
                orderedList(local("tabs"), s.order.map { OrderedItem(it, it) }, on.move, on.remove, RowAction("Add tab", AppIcon.ActionPlus) { on.event("add") })
            }
            section("records", heading = "Records") {
                records(local("records"), s.records, "Nothing to review", { on.event("record:$it") }, EntryAction("Open", AppIcon.ActionChevronRight) { on.event("trailing:$it") })
            }
        }
    }
}

/** Named sample states. Pairs for invariant I9: within capacity and over capacity, loading and ready. */
internal val SpecimenFixtures: List<Fixture<SpecimenState>> = SpecimenTab.entries.flatMap { tab -> listOf(
    Fixture("${tab.name}-ready", SpecimenState(tab)),
    Fixture("${tab.name}-loading", SpecimenState(tab, loading = true)),
    Fixture("${tab.name}-long", SpecimenState(tab, long = true, choice = "carousel", records = specimenRecords(3, true))),
) } + listOf(
    Fixture("Data-syncing", SpecimenState(SpecimenTab.Data, syncing = true)),
    Fixture("Data-empty", SpecimenState(SpecimenTab.Data, order = emptyList(), records = specimenRecords(0, false))),
    Fixture("Data-many-records", SpecimenState(SpecimenTab.Data, records = specimenRecords(400, false))))

/** The live specimen keeps its own sample values so every control can be operated. */
internal val SpecimenSource = PageSource(PageId.Specimen, state = {
    var state by remember { mutableStateOf(SpecimenState()) }
    val actions = remember {
        SpecimenActions(tab = { state = state.with(tab = it) }, toggle = { state = state.with(toggle = it) }, choice = { state = state.with(choice = it) },
            slider = { state = state.with(slider = it) }, text = { state = state.with(text = it) }, name = { state = state.with(name = it) },
            move = { id, to -> state = state.with(order = state.order.toMutableList().apply { remove(id); add(to.coerceIn(0, size), id) }) },
            remove = { id -> state = state.with(order = state.order - id) },
            event = { if (it == "sync" || it == "stop") state = state.with(syncing = it == "sync") })
    }
    state.with(on = actions)
}, declare = ::specimenPage, fixtures = SpecimenFixtures)

private fun SpecimenState.with(tab: SpecimenTab = this.tab, toggle: Boolean = this.toggle, choice: String = this.choice, slider: Float = this.slider,
    text: String = this.text, name: String = this.name, syncing: Boolean = this.syncing, order: List<String> = this.order, on: SpecimenActions = this.on) =
    SpecimenState(tab, toggle, choice, slider, text, name, loading, long, syncing, order, records, on)
