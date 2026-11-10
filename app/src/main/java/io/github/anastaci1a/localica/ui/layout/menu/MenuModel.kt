package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.runtime.Immutable
import io.github.anastaci1a.localica.ui.spec.MarkerTone
import java.util.Collections

/**
 * #757 declaration model, builder and admission (DESIGN 3.2, 3.3, 4).
 *
 * Construction boundary: every content type below is a sealed interface whose only implementations are the
 * file-private final classes in this file. Nothing outside this file can construct one. The builder creates them
 * inside a [PageDraft], which exposes nothing; [AcceptedPage.admit] is the single factory that hands content out,
 * and only for a draft that passed every structural rule. Hosts take [AcceptedPage] in their signatures.
 */
@JvmInline value class QualifiedKey(val path: String) { override fun toString() = path }

enum class ActionRole { Default, Destructive }
enum class NoticeTone { Info, Warning, Error }
enum class FieldKeyboard { Text, Number, Uri }
/** The closed slot set of an action key `<owner key>#<slot>`. */
enum class ActionSlot { Click, Change, Select, Move, Remove, Trailing, Confirm, Dismiss, FixAction }

/** A loadable value. [Loading] reserves [lines] of the slot's role, so loading and ready faces share geometry within capacity. */
@Immutable sealed interface Value<out T> {
    class Loading(val lines: Int = 1) : Value<Nothing> {
        override fun equals(other: Any?) = other is Loading && lines == other.lines
        override fun hashCode() = lines
    }
    class Ready<T>(val value: T) : Value<T> {
        override fun equals(other: Any?) = other is Ready<*> && value == other.value
        override fun hashCode() = value.hashCode()
    }
}

// ---- Content: read-only views. Strings, numbers and enums only; no caller object, controller or lambda. ----

@Immutable sealed interface PageContent { val pageKey: String; val title: String; val body: BodyContent }
@Immutable sealed interface BodyContent
sealed interface SectionsBody : BodyContent { val sections: List<SectionContent> }
sealed interface TabsBody : BodyContent { val key: QualifiedKey; val tabs: List<TabContent>; val selected: String }
@Immutable sealed interface TabContent { val key: QualifiedKey; val id: String; val label: String; val sections: List<SectionContent> }
@Immutable sealed interface SectionContent { val key: QualifiedKey; val heading: String?; val caption: String?; val rows: List<RowContent> }
@Immutable sealed interface OptionContent { val id: String; val label: String }
@Immutable sealed interface RowContent { val key: QualifiedKey; val enabled: Boolean; val opens: PopupContent? }
sealed interface NavContent : RowContent { val title: String; val summary: String?; val iconKey: String?; val target: String }
sealed interface LinkContent : RowContent { val title: String; val summary: String?; val iconKey: String?; val marker: MarkerTone? }
sealed interface ValueContent : RowContent { val title: String; val value: String }
sealed interface ChoiceContent : RowContent { val title: String; val options: List<OptionContent>; val selected: String; val helper: String? }
sealed interface ToggleContent : RowContent { val title: String; val checked: Boolean; val helper: String? }
sealed interface SliderContent : RowContent { val title: String; val value: Float; val start: Float; val end: Float; val steps: Int
    val curve: Float; val valueLabel: String; val low: String?; val high: String?; val helper: String? }
sealed interface ActionContent : RowContent { val title: String; val iconKey: String; val role: ActionRole }
sealed interface FieldContent : RowContent { val title: String; val text: String; val keyboard: FieldKeyboard; val error: String? }
sealed interface InfoContent : RowContent { val infoKey: String; val data: List<String>; val title: String; val value: Value<String>
    val iconKey: String?; val clickable: Boolean }
sealed interface ProgressContent : RowContent { val fraction: Float?; val status: String }
sealed interface NoticeContent : RowContent { val tone: NoticeTone; val text: String; val fixLabel: String?; val fixIconKey: String? }
@Immutable sealed interface InfoLineContent { val key: QualifiedKey; val infoKey: String; val data: List<String>; val value: Value<String> }
@Immutable sealed interface StatContent { val key: QualifiedKey; val infoKey: String; val data: List<String>; val label: String; val value: Value<String> }
sealed interface StatusCardContent : RowContent { val progress: Float?; val iconKey: String?; val title: String
    val infos: List<InfoLineContent>; val stats: List<StatContent>; val actions: List<ActionContent> }
@Immutable sealed interface LegendEntryContent { val key: QualifiedKey; val id: String; val tone: MarkerTone; val title: String
    val count: Value<Long>; val clickable: Boolean }
sealed interface LegendContent : RowContent { val bar: Boolean; val entries: List<LegendEntryContent> }
@Immutable sealed interface OrderedItemContent { val key: QualifiedKey; val id: String; val title: String; val subtitle: String? }
sealed interface OrderedListContent : RowContent { val items: List<OrderedItemContent>; val removable: Boolean
    val addLabel: String?; val addIconKey: String? }
sealed interface RecordsContent : RowContent { val snapshot: RecordSnapshot; val empty: String; val clickable: Boolean
    val trailingLabel: String?; val trailingIconKey: String? }
sealed interface PreviewContent : RowContent { val previewKey: String }

@Immutable sealed interface PopupContent { val key: QualifiedKey; val title: String }
sealed interface ConfirmContent : PopupContent { val message: String; val confirmLabel: String; val confirmIconKey: String?
    val role: ActionRole; val dismissLabel: String }
sealed interface MessageContent : PopupContent { val message: String; val tone: NoticeTone?; val dismissLabel: String }
sealed interface TextEntryContent : PopupContent { val label: String; val initial: String; val error: String?; val keyboard: FieldKeyboard
    val confirmLabel: String; val dismissLabel: String }
sealed interface SingleChoiceContent : PopupContent { val options: List<OptionContent>; val selected: String; val staged: Boolean
    val confirmLabel: String; val dismissLabel: String }

// ---- Bindings: the typed handler of one action key. Never compared, never reachable from content. ----

sealed interface Binding {
    class Click(val run: () -> Unit) : Binding
    class Toggle(val run: (Boolean) -> Unit) : Binding
    class Number(val run: (Float) -> Unit) : Binding
    class Text(val run: (String) -> Unit) : Binding
    /** An option, tab or entry, by id. Never an index. */
    class Id(val run: (String) -> Unit) : Binding
    class Move(val run: (String, Int) -> Unit) : Binding
}

/** `ActionKey -> typed handler` of one declaration. Replaced on every publish; never memoized. */
class BindingTable internal constructor(private val handlers: Map<String, Binding>) {
    val size: Int get() = handlers.size
    internal operator fun get(owner: String, slot: ActionSlot): Binding? = handlers[actionKey(owner, slot)]
}
internal fun actionKey(owner: String, slot: ActionSlot) = "$owner#$slot"

/** What the dispatcher checks an event against: is the owner present and enabled, and does the payload id exist now. */
class OwnerState internal constructor(val enabled: Boolean, private val ids: Set<String>?, private val records: RecordSnapshot?) {
    fun accepts(id: String): Boolean = records?.contains(id) ?: ids?.contains(id) ?: false
}
class ContentIndex internal constructor(private val owners: Map<String, OwnerState>, private val popups: Map<String, PopupContent>) {
    val keys: Set<String> get() = owners.keys
    operator fun get(owner: String): OwnerState? = owners[owner]
    /** The popup content currently declared under a popup key, if its owner still declares it. */
    fun popup(key: String): PopupContent? = popups[key]
}

enum class AdmissionRule { DuplicateKey, DuplicateSetting, DuplicateInfo, OverlappingDatum, BlankText, TabCount, CardActionCount, HeaderInfoCount,
    UnknownSelection, InvalidNumber }
/** Names the page, the rule and the qualified key. Never a title, a value or form text. */
class Violation internal constructor(val page: String, val rule: AdmissionRule, val key: String) {
    override fun toString() = "$page: $rule at $key"
    override fun equals(other: Any?) = other is Violation && page == other.page && rule == other.rule && key == other.key
    override fun hashCode() = 31 * (31 * page.hashCode() + rule.hashCode()) + key.hashCode()
}

/** What the builder returns. Not renderable and not readable; pass it to [AcceptedPage.admit] or an [AdmissionCache]. */
sealed interface PageDraft

/** The only renderable form of a page. */
sealed interface AcceptedPage {
    val content: PageContent
    val bindings: BindingTable
    val index: ContentIndex
    companion object {
        /** The single factory. Total: runs every structural rule and returns the accepted page or every violation. */
        fun admit(draft: PageDraft): Admission = admitDraft(draft as Draft)
    }
}

sealed interface Admission {
    val pageKey: String
    class Accepted internal constructor(val page: AcceptedPage) : Admission { override val pageKey: String get() = page.content.pageKey }
    class Rejected internal constructor(override val pageKey: String, val violations: List<Violation>) : Admission
}

/**
 * Reuses the previous accepted content instance and verdict when a draft's content is structurally equal, so equal
 * rows are skipped. The result always carries the current draft's bindings; bindings are never cached.
 */
class AdmissionCache {
    private var lastDraft: Draft? = null
    private var last: Admission? = null
    /** How many times admission actually ran; equal content must not raise it. */
    var admissions: Int = 0
        private set

    fun admit(draft: PageDraft): Admission {
        draft as Draft
        val previous = last
        val result = if (previous != null && lastDraft?.page == draft.page) when (previous) {
            is Admission.Accepted -> Admission.Accepted(AcceptedNode(previous.page.content, BindingTable(draft.bindings), previous.page.index))
            is Admission.Rejected -> previous
        } else { admissions++; admitDraft(draft) }
        lastDraft = draft
        last = result
        return result
    }
}

// ---- Builder: non-composable, so a composable call inside a declaration is a compile error. ----

@DslMarker annotation class MenuDsl

/** Leading adornment of a link: an icon or a marker, both in the shared leading slot. */
sealed interface Lead {
    class Icon(val icon: MenuIcon) : Lead
    class Marker(val tone: MarkerTone) : Lead
}
class Option(val id: String, val label: String)
class OrderedItem(val id: String, val title: String, val subtitle: String? = null)
class ConfirmAction(val label: String, val icon: MenuIcon? = null, val role: ActionRole = ActionRole.Default, val onConfirm: () -> Unit)
class RowAction(val label: String, val icon: MenuIcon, val onClick: () -> Unit)
class EntryAction(val label: String, val icon: MenuIcon, val onClick: (String) -> Unit)

/** Popup intent: declared with `opens = ...` on a home-capable row. Its content is admitted with its owner. */
sealed interface PopupSpec

fun confirm(title: String, message: String, confirm: ConfirmAction, dismissLabel: String = "Cancel", onDismiss: (() -> Unit)? = null): PopupSpec =
    Popup({ key -> ConfirmNode(key, title, message, confirm.label, confirm.icon?.key, confirm.role, dismissLabel) },
        listOfNotNull(ActionSlot.Confirm to Binding.Click(confirm.onConfirm), onDismiss?.let { ActionSlot.Dismiss to Binding.Click(it) }))

fun message(title: String, message: String, tone: NoticeTone? = null, dismissLabel: String = "OK", onDismiss: (() -> Unit)? = null): PopupSpec =
    Popup({ key -> MessageNode(key, title, message, tone, dismissLabel) }, listOfNotNull(onDismiss?.let { ActionSlot.Dismiss to Binding.Click(it) }))

/** The editor draft belongs to the popup owner; [onConfirm] receives the confirmed text. */
fun textEntry(title: String, label: String, initial: String, onConfirm: (String) -> Unit, confirmLabel: String = "Save", error: String? = null,
    keyboard: FieldKeyboard = FieldKeyboard.Text, dismissLabel: String = "Cancel", onDismiss: (() -> Unit)? = null): PopupSpec =
    Popup({ key -> TextEntryNode(key, title, label, initial, error, keyboard, confirmLabel, dismissLabel) },
        listOfNotNull(ActionSlot.Confirm to Binding.Text(onConfirm), onDismiss?.let { ActionSlot.Dismiss to Binding.Click(it) }))

/** Staged: [onSelect] is called once on Confirm. Immediate: on every selection. */
fun singleChoice(title: String, options: List<Option>, selected: String, onSelect: (String) -> Unit, staged: Boolean = true,
    confirmLabel: String = "Select", dismissLabel: String = "Cancel", onDismiss: (() -> Unit)? = null): PopupSpec =
    Popup({ key -> SingleChoiceNode(key, title, options(options), selected, staged, confirmLabel, dismissLabel) },
        listOfNotNull((if (staged) ActionSlot.Confirm else ActionSlot.Select) to Binding.Id(onSelect), onDismiss?.let { ActionSlot.Dismiss to Binding.Click(it) }))

fun page(ref: PageRef, build: PageScope.() -> Unit): PageDraft {
    val part = PagePart(ref.key)
    PageScope(part).build()
    return Draft(PageNode(ref.key, ref.title, part.body()), part.bindings)
}

/**
 * Opaque builder storage. Scopes hold their unchecked nodes only through this type, whose implementations are private to
 * this file, so a scope has no member that returns content: the draft is the only thing that leaves the builder.
 */
sealed class DraftPart

@MenuDsl class PageScope internal constructor(private val part: DraftPart) {
    private val state get() = part as PagePart
    private val page get() = state.page
    private val bindings get() = state.bindings

    fun section(id: String, heading: String? = null, caption: String? = null, build: SectionScope.() -> Unit) {
        val section = SectionPart("$page/$id", bindings)
        SectionScope(section).build()
        state.sections += section.node(heading, caption)
    }

    /** Page level only; a page declares tabs or sections. A second call replaces nothing: admission reports the duplicate key. */
    fun <T : TabId> tabs(selected: T, onSelect: (T) -> Unit, build: TabsScope<T>.() -> Unit) {
        val scope = TabsPart<T>(page, bindings)
        TabsScope<T>(scope).build()
        val byKey = scope.ids
        bindings[actionKey(page, ActionSlot.Select)] = Binding.Id { key -> byKey[key]?.let(onSelect) }
        val node = TabsNode(QualifiedKey(page), frozen(scope.tabs), selected.key)
        state.tabs = state.tabs?.let { TabsNode(it.key, frozen(it.tabs + node.tabs), it.selected) } ?: node
    }
}

@MenuDsl class TabsScope<T : TabId> internal constructor(private val part: DraftPart) {
    fun tab(id: T, label: String, build: TabScope.() -> Unit) {
        @Suppress("UNCHECKED_CAST") val state = part as TabsPart<T>
        state.ids[id.key] = id
        val tab = TabPart("${state.page}/${id.key}", state.bindings)
        TabScope(tab).build()
        state.tabs += TabNode(QualifiedKey(tab.tab), id.key, label, frozen(tab.sections))
    }
}

@MenuDsl class TabScope internal constructor(private val part: DraftPart) {
    fun section(id: String, heading: String? = null, caption: String? = null, build: SectionScope.() -> Unit) {
        val state = part as TabPart
        val section = SectionPart("${state.tab}/$id", state.bindings)
        SectionScope(section).build()
        state.sections += section.node(heading, caption)
    }
}

@MenuDsl class SectionScope internal constructor(private val part: DraftPart) {
    private val section get() = (part as SectionPart).section
    private val bindings get() = (part as SectionPart).bindings
    private val rows get() = (part as SectionPart).rows

    private fun key(source: RowKeySource) = QualifiedKey(when (source) {
        is SettingKey -> "$section/setting:${source.id}"
        is LocalKey -> "$section/local:${source.id}"
    })
    private fun title(source: RowKeySource) = when (source) { is SettingKey -> source.title; is LocalKey -> source.title }
    private fun bind(owner: QualifiedKey, slot: ActionSlot, binding: Binding?) { if (binding != null) bindings[actionKey(owner.path, slot)] = binding }
    private fun opens(owner: QualifiedKey, spec: PopupSpec?): PopupContent? = (spec as Popup?)?.let { popup ->
        val key = QualifiedKey("${owner.path}/popup")
        popup.bindings.forEach { (slot, binding) -> bind(key, slot, binding) }
        popup.content(key)
    }

    /** A navigation row: icon, title and summary come from the page registry and cannot drift. */
    fun nav(page: PageRef) {
        rows += NavNode(QualifiedKey("$section/local:nav:${page.key}"), page.title, page.summary, page.icon?.key, page.key)
    }

    fun link(key: RowKeySource, summary: String? = null, leading: Lead? = null, enabled: Boolean = true, opens: PopupSpec? = null,
        onClick: (() -> Unit)? = null) {
        val k = key(key)
        bind(k, ActionSlot.Click, onClick?.let(Binding::Click))
        rows += LinkNode(k, title(key), summary, (leading as? Lead.Icon)?.icon?.key, (leading as? Lead.Marker)?.tone, enabled, opens(k, opens))
    }

    /** Shows the current value and opens its editor popup. */
    fun value(key: RowKeySource, value: String, opens: PopupSpec, enabled: Boolean = true) {
        val k = key(key)
        rows += ValueNode(k, title(key), value, enabled, opens(k, opens))
    }

    /** Inline segmented control when every label fits on one line, otherwise the choice box; the host decides by measuring. */
    fun choice(key: RowKeySource, options: List<Option>, selected: String, onSelect: (String) -> Unit, helper: String? = null, enabled: Boolean = true) {
        val k = key(key)
        val choices = options(options)
        // The choice box opens the standard staged popup; it is part of this row's declaration.
        val popup = QualifiedKey("${k.path}/popup")
        bind(k, ActionSlot.Select, Binding.Id(onSelect))
        bind(popup, ActionSlot.Confirm, Binding.Id(onSelect))
        rows += ChoiceNode(k, title(key), choices, selected, helper, enabled, SingleChoiceNode(popup, title(key), choices, selected, true, "Select", "Cancel"))
    }

    fun toggle(key: RowKeySource, checked: Boolean, onChange: (Boolean) -> Unit, helper: String? = null, enabled: Boolean = true) {
        val k = key(key)
        bind(k, ActionSlot.Change, Binding.Toggle(onChange))
        rows += ToggleNode(k, title(key), checked, helper, enabled)
    }

    fun slider(key: RowKeySource, value: Float, onChange: (Float) -> Unit, valueLabel: String, range: ClosedFloatingPointRange<Float> = 0f..1f,
        steps: Int = 0, curve: Float = 1f, low: String? = null, high: String? = null, helper: String? = null, enabled: Boolean = true) {
        val k = key(key)
        bind(k, ActionSlot.Change, Binding.Number(onChange))
        rows += SliderNode(k, title(key), value, range.start, range.endInclusive, steps, curve, valueLabel, low, high, helper, enabled)
    }

    fun action(key: RowKeySource, icon: MenuIcon, role: ActionRole = ActionRole.Default, enabled: Boolean = true, opens: PopupSpec? = null,
        onClick: (() -> Unit)? = null) {
        rows += actionNode(key(key), title(key), icon, role, enabled, opens, onClick)
    }

    private fun actionNode(k: QualifiedKey, title: String, icon: MenuIcon, role: ActionRole, enabled: Boolean, opens: PopupSpec?,
        onClick: (() -> Unit)?): ActionContent {
        bind(k, ActionSlot.Click, onClick?.let(Binding::Click))
        return ActionNode(k, title, icon.key, role, enabled, opens(k, opens))
    }

    fun field(key: RowKeySource, text: String, onChange: (String) -> Unit, keyboard: FieldKeyboard = FieldKeyboard.Text, error: String? = null,
        enabled: Boolean = true) {
        val k = key(key)
        bind(k, ActionSlot.Change, Binding.Text(onChange))
        rows += FieldNode(k, title(key), text, keyboard, error, enabled)
    }

    fun info(info: InfoKey, title: String, value: Value<String>, icon: MenuIcon? = null, onClick: (() -> Unit)? = null) {
        val k = QualifiedKey("$section/info:${info.key}")
        bind(k, ActionSlot.Click, onClick?.let(Binding::Click))
        rows += InfoNode(k, info.key, data(info), title, value, icon?.key, onClick != null)
    }

    /** [fraction] null is indeterminate. */
    fun progress(id: String, fraction: Float?, status: String) {
        rows += ProgressNode(QualifiedKey("$section/local:$id"), fraction, status)
    }

    fun notice(id: String, tone: NoticeTone, text: String, fix: RowAction? = null) {
        val k = QualifiedKey("$section/local:$id")
        bind(k, ActionSlot.FixAction, fix?.let { Binding.Click(it.onClick) })
        rows += NoticeNode(k, tone, text, fix?.label, fix?.icon?.key)
    }

    fun statusCard(key: RowKeySource, build: StatusCardScope.() -> Unit) {
        val k = key(key)
        val scope = CardPart(k.path) { actionKey, title, icon, role, enabled, opens, onClick -> actionNode(actionKey, title, icon, role, enabled, opens, onClick) }
        StatusCardScope(scope).build()
        rows += StatusCardNode(k, scope.progress, scope.icon, scope.title, frozen(scope.infos), frozen(scope.stats), frozen(scope.actions))
    }

    fun legend(key: RowKeySource, bar: Boolean = false, build: LegendScope.() -> Unit) {
        val k = key(key)
        val scope = LegendPart(k.path)
        LegendScope(scope).build()
        val handlers = scope.handlers
        if (handlers.isNotEmpty()) bind(k, ActionSlot.Click, Binding.Id { id -> handlers[id]?.invoke() })
        rows += LegendNode(k, bar, frozen(scope.entries))
    }

    fun orderedList(key: RowKeySource, items: List<OrderedItem>, onMove: (String, Int) -> Unit, onRemove: ((String) -> Unit)? = null,
        add: RowAction? = null) {
        val k = key(key)
        bind(k, ActionSlot.Move, Binding.Move(onMove))
        bind(k, ActionSlot.Remove, onRemove?.let(Binding::Id))
        bind(k, ActionSlot.Click, add?.let { Binding.Click(it.onClick) })
        rows += OrderedListNode(k, frozen(items.map { OrderedItemNode(QualifiedKey("${k.path}/item:${it.id}"), it.id, it.title, it.subtitle) }),
            onRemove != null, add?.label, add?.icon?.key)
    }

    /** A record list. [snapshot] carries the proof that its ids are unique; entries become separate lazy items. */
    fun records(key: RowKeySource, snapshot: RecordSnapshot, empty: String, onClick: ((String) -> Unit)? = null, trailing: EntryAction? = null) {
        val k = key(key)
        bind(k, ActionSlot.Click, onClick?.let(Binding::Id))
        bind(k, ActionSlot.Trailing, trailing?.let { Binding.Id(it.onClick) })
        rows += RecordsNode(k, snapshot, empty, onClick != null, trailing?.label, trailing?.icon?.key)
    }

    /** The one escape hatch on pages: custom drawing registered in the host; the system owns its frame. */
    fun preview(id: PreviewId) {
        rows += PreviewNode(QualifiedKey("$section/local:preview:${id.key}"), id.key)
    }
}

@MenuDsl class StatusCardScope internal constructor(private val part: DraftPart) {
    fun header(title: String, progress: Float? = null, icon: MenuIcon? = null, build: HeaderScope.() -> Unit = {}) {
        val state = part as CardPart
        state.title = title; state.progress = progress; state.icon = icon?.key
        HeaderScope(part).build()
    }
    /** Any count; the grid reflows. */
    fun stats(build: StatsScope.() -> Unit) { StatsScope(part).build() }
    fun actions(build: CardActionsScope.() -> Unit) { CardActionsScope(part).build() }
}

@MenuDsl class HeaderScope internal constructor(private val part: DraftPart) {
    fun info(info: InfoKey, value: Value<String>) {
        val state = part as CardPart
        state.infos += InfoLineNode(QualifiedKey("${state.card}/info:${info.key}"), info.key, data(info), value)
    }
}

@MenuDsl class StatsScope internal constructor(private val part: DraftPart) {
    fun stat(info: InfoKey, label: String, value: Value<String>) {
        val state = part as CardPart
        state.stats += StatNode(QualifiedKey("${state.card}/info:${info.key}"), info.key, data(info), label, value)
    }
}

@MenuDsl class CardActionsScope internal constructor(private val part: DraftPart) {
    fun action(key: SettingKey, icon: MenuIcon, role: ActionRole = ActionRole.Default, enabled: Boolean = true, opens: PopupSpec? = null,
        onClick: (() -> Unit)? = null) {
        val state = part as CardPart
        state.actions += state.action(QualifiedKey("${state.card}/setting:${key.id}"), key.title, icon, role, enabled, opens, onClick)
    }
}

@MenuDsl class LegendScope internal constructor(private val part: DraftPart) {
    fun entry(id: String, tone: MarkerTone, title: String, count: Value<Long>, onClick: (() -> Unit)? = null) {
        val state = part as LegendPart
        if (onClick != null) state.handlers[id] = onClick
        state.entries += LegendEntryNode(QualifiedKey("${state.legend}/item:$id"), id, tone, title, count, onClick != null)
    }
}

// ---- Builder storage: unchecked nodes stay in these file-private classes until admission. ----

private class PagePart(val page: String) : DraftPart() {
    val bindings = HashMap<String, Binding>()
    val sections = ArrayList<SectionContent>()
    var tabs: TabsNode? = null
    fun body(): BodyContent = tabs ?: SectionsNode(frozen(sections))
}
private class TabsPart<T : TabId>(val page: String, val bindings: HashMap<String, Binding>) : DraftPart() {
    val tabs = ArrayList<TabContent>()
    val ids = HashMap<String, T>()
}
private class TabPart(val tab: String, val bindings: HashMap<String, Binding>) : DraftPart() { val sections = ArrayList<SectionContent>() }
private class SectionPart(val section: String, val bindings: HashMap<String, Binding>) : DraftPart() {
    val rows = ArrayList<RowContent>()
    fun node(heading: String?, caption: String?): SectionContent = SectionNode(QualifiedKey(section), heading, caption, frozen(rows))
}
private class CardPart(val card: String,
    val action: (QualifiedKey, String, MenuIcon, ActionRole, Boolean, PopupSpec?, (() -> Unit)?) -> ActionContent) : DraftPart() {
    var progress: Float? = null
    var icon: String? = null
    var title: String = ""
    val infos = ArrayList<InfoLineContent>()
    val stats = ArrayList<StatContent>()
    val actions = ArrayList<ActionContent>()
}
private class LegendPart(val legend: String) : DraftPart() {
    val entries = ArrayList<LegendEntryContent>()
    val handlers = HashMap<String, () -> Unit>()
}

// ---- File-private implementations. Final, not data classes (no copy), structural equality, immutable lists. ----

private fun <T> frozen(list: List<T>): List<T> = Collections.unmodifiableList(ArrayList(list))
private fun options(options: List<Option>): List<OptionContent> = frozen(options.map { OptionNode(it.id, it.label) })
private fun data(info: InfoKey): List<String> = frozen(info.data.map { it.key }.sorted())

private abstract class Node {
    protected abstract fun parts(): Array<Any?>
    final override fun equals(other: Any?) = other === this || (other is Node && other.javaClass == javaClass && parts().contentEquals(other.parts()))
    final override fun hashCode() = parts().contentHashCode()
    final override fun toString() = javaClass.simpleName + parts().firstOrNull()?.let { "($it)" }.orEmpty()
}

private class Popup(val content: (QualifiedKey) -> PopupContent, val bindings: List<Pair<ActionSlot, Binding>>) : PopupSpec
private class Draft(val page: PageNode, val bindings: Map<String, Binding>) : PageDraft
private class AcceptedNode(override val content: PageContent, override val bindings: BindingTable, override val index: ContentIndex) : AcceptedPage

private class PageNode(override val pageKey: String, override val title: String, override val body: BodyContent) : Node(), PageContent {
    override fun parts() = arrayOf<Any?>(pageKey, title, body) }
private class SectionsNode(override val sections: List<SectionContent>) : Node(), SectionsBody { override fun parts() = arrayOf<Any?>(sections) }
private class TabsNode(override val key: QualifiedKey, override val tabs: List<TabContent>, override val selected: String) : Node(), TabsBody {
    override fun parts() = arrayOf<Any?>(key, tabs, selected) }
private class TabNode(override val key: QualifiedKey, override val id: String, override val label: String,
    override val sections: List<SectionContent>) : Node(), TabContent { override fun parts() = arrayOf<Any?>(key, id, label, sections) }
private class SectionNode(override val key: QualifiedKey, override val heading: String?, override val caption: String?,
    override val rows: List<RowContent>) : Node(), SectionContent { override fun parts() = arrayOf<Any?>(key, heading, caption, rows) }
private class OptionNode(override val id: String, override val label: String) : Node(), OptionContent { override fun parts() = arrayOf<Any?>(id, label) }

private class NavNode(override val key: QualifiedKey, override val title: String, override val summary: String?, override val iconKey: String?,
    override val target: String) : Node(), NavContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, title, summary, iconKey, target) }
private class LinkNode(override val key: QualifiedKey, override val title: String, override val summary: String?, override val iconKey: String?,
    override val marker: MarkerTone?, override val enabled: Boolean, override val opens: PopupContent?) : Node(), LinkContent {
    override fun parts() = arrayOf<Any?>(key, title, summary, iconKey, marker, enabled, opens) }
private class ValueNode(override val key: QualifiedKey, override val title: String, override val value: String, override val enabled: Boolean,
    override val opens: PopupContent?) : Node(), ValueContent { override fun parts() = arrayOf<Any?>(key, title, value, enabled, opens) }
private class ChoiceNode(override val key: QualifiedKey, override val title: String, override val options: List<OptionContent>,
    override val selected: String, override val helper: String?, override val enabled: Boolean, override val opens: PopupContent?) : Node(), ChoiceContent {
    override fun parts() = arrayOf<Any?>(key, title, options, selected, helper, enabled, opens) }
private class ToggleNode(override val key: QualifiedKey, override val title: String, override val checked: Boolean, override val helper: String?,
    override val enabled: Boolean) : Node(), ToggleContent {
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, title, checked, helper, enabled) }
private class SliderNode(override val key: QualifiedKey, override val title: String, override val value: Float, override val start: Float,
    override val end: Float, override val steps: Int, override val curve: Float, override val valueLabel: String, override val low: String?,
    override val high: String?, override val helper: String?, override val enabled: Boolean) : Node(), SliderContent {
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, title, value, start, end, steps, curve, valueLabel, low, high, helper, enabled) }
private class ActionNode(override val key: QualifiedKey, override val title: String, override val iconKey: String, override val role: ActionRole,
    override val enabled: Boolean, override val opens: PopupContent?) : Node(), ActionContent {
    override fun parts() = arrayOf<Any?>(key, title, iconKey, role, enabled, opens) }
private class FieldNode(override val key: QualifiedKey, override val title: String, override val text: String, override val keyboard: FieldKeyboard,
    override val error: String?, override val enabled: Boolean) : Node(), FieldContent {
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, title, text, keyboard, error, enabled) }
private class InfoNode(override val key: QualifiedKey, override val infoKey: String, override val data: List<String>, override val title: String,
    override val value: Value<String>, override val iconKey: String?, override val clickable: Boolean) : Node(), InfoContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, infoKey, data, title, value, iconKey, clickable) }
private class ProgressNode(override val key: QualifiedKey, override val fraction: Float?, override val status: String) : Node(), ProgressContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, fraction, status) }
private class NoticeNode(override val key: QualifiedKey, override val tone: NoticeTone, override val text: String, override val fixLabel: String?,
    override val fixIconKey: String?) : Node(), NoticeContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, tone, text, fixLabel, fixIconKey) }
private class InfoLineNode(override val key: QualifiedKey, override val infoKey: String, override val data: List<String>,
    override val value: Value<String>) : Node(), InfoLineContent { override fun parts() = arrayOf<Any?>(key, infoKey, data, value) }
private class StatNode(override val key: QualifiedKey, override val infoKey: String, override val data: List<String>, override val label: String,
    override val value: Value<String>) : Node(), StatContent { override fun parts() = arrayOf<Any?>(key, infoKey, data, label, value) }
private class StatusCardNode(override val key: QualifiedKey, override val progress: Float?, override val iconKey: String?, override val title: String,
    override val infos: List<InfoLineContent>, override val stats: List<StatContent>, override val actions: List<ActionContent>) : Node(), StatusCardContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, progress, iconKey, title, infos, stats, actions) }
private class LegendEntryNode(override val key: QualifiedKey, override val id: String, override val tone: MarkerTone, override val title: String,
    override val count: Value<Long>, override val clickable: Boolean) : Node(), LegendEntryContent {
    override fun parts() = arrayOf<Any?>(key, id, tone, title, count, clickable) }
private class LegendNode(override val key: QualifiedKey, override val bar: Boolean, override val entries: List<LegendEntryContent>) : Node(), LegendContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, bar, entries) }
private class OrderedItemNode(override val key: QualifiedKey, override val id: String, override val title: String, override val subtitle: String?) :
    Node(), OrderedItemContent { override fun parts() = arrayOf<Any?>(key, id, title, subtitle) }
private class OrderedListNode(override val key: QualifiedKey, override val items: List<OrderedItemContent>, override val removable: Boolean,
    override val addLabel: String?, override val addIconKey: String?) : Node(), OrderedListContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, items, removable, addLabel, addIconKey) }
private class RecordsNode(override val key: QualifiedKey, override val snapshot: RecordSnapshot, override val empty: String,
    override val clickable: Boolean, override val trailingLabel: String?, override val trailingIconKey: String?) : Node(), RecordsContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, snapshot, empty, clickable, trailingLabel, trailingIconKey) }
private class PreviewNode(override val key: QualifiedKey, override val previewKey: String) : Node(), PreviewContent {
    override val enabled get() = true
    override val opens: PopupContent? get() = null
    override fun parts() = arrayOf<Any?>(key, previewKey) }

private class ConfirmNode(override val key: QualifiedKey, override val title: String, override val message: String, override val confirmLabel: String,
    override val confirmIconKey: String?, override val role: ActionRole, override val dismissLabel: String) : Node(), ConfirmContent {
    override fun parts() = arrayOf<Any?>(key, title, message, confirmLabel, confirmIconKey, role, dismissLabel) }
private class MessageNode(override val key: QualifiedKey, override val title: String, override val message: String, override val tone: NoticeTone?,
    override val dismissLabel: String) : Node(), MessageContent { override fun parts() = arrayOf<Any?>(key, title, message, tone, dismissLabel) }
private class TextEntryNode(override val key: QualifiedKey, override val title: String, override val label: String, override val initial: String,
    override val error: String?, override val keyboard: FieldKeyboard, override val confirmLabel: String, override val dismissLabel: String) :
    Node(), TextEntryContent { override fun parts() = arrayOf<Any?>(key, title, label, initial, error, keyboard, confirmLabel, dismissLabel) }
private class SingleChoiceNode(override val key: QualifiedKey, override val title: String, override val options: List<OptionContent>,
    override val selected: String, override val staged: Boolean, override val confirmLabel: String, override val dismissLabel: String) :
    Node(), SingleChoiceContent { override fun parts() = arrayOf<Any?>(key, title, options, selected, staged, confirmLabel, dismissLabel) }

// ---- Admission: structural, total, and independent of record count. ----

private class Admitter(val page: String) {
    val violations = ArrayList<Violation>()
    val owners = HashMap<String, OwnerState>()
    val popups = HashMap<String, PopupContent>()
    private val keys = HashSet<String>()
    private val settings = HashSet<String>()
    private var infos = HashSet<String>()
    private var data = HashSet<String>()

    fun fail(rule: AdmissionRule, key: String) { violations += Violation(page, rule, key) }
    fun key(key: QualifiedKey) { if (!keys.add(key.path)) fail(AdmissionRule.DuplicateKey, key.path) }
    fun text(value: String?, key: QualifiedKey) { if (value != null && value.isBlank()) fail(AdmissionRule.BlankText, key.path) }
    fun required(value: String, key: QualifiedKey) { if (value.isBlank()) fail(AdmissionRule.BlankText, key.path) }
    fun number(ok: Boolean, key: QualifiedKey) { if (!ok) fail(AdmissionRule.InvalidNumber, key.path) }
    private fun owner(key: QualifiedKey, enabled: Boolean = true, ids: Set<String>? = null, records: RecordSnapshot? = null) {
        owners[key.path] = OwnerState(enabled, ids, records)
    }
    private fun setting(key: QualifiedKey) {
        val id = key.path.substringAfterLast("/setting:", "")
        if (id.isNotEmpty() && "/setting:" in key.path && !settings.add(id)) fail(AdmissionRule.DuplicateSetting, key.path)
    }
    private fun shown(key: QualifiedKey, infoKey: String, shownData: List<String>) {
        if (!infos.add(infoKey)) fail(AdmissionRule.DuplicateInfo, key.path)
        if (shownData.any { !data.add(it) }) fail(AdmissionRule.OverlappingDatum, key.path)
    }
    private fun options(key: QualifiedKey, options: List<OptionContent>, selected: String): Set<String> {
        val ids = HashSet<String>()
        options.forEach { if (!ids.add(it.id)) fail(AdmissionRule.DuplicateKey, "${key.path}/option:${it.id}"); required(it.label, key) }
        if (selected !in ids) fail(AdmissionRule.UnknownSelection, key.path)
        return ids
    }

    fun body(body: BodyContent) {
        when (body) {
            is SectionsBody -> panel(body.sections)
            is TabsBody -> {
                if (body.tabs.size !in 2..4) fail(AdmissionRule.TabCount, body.key.path)
                val ids = HashSet<String>()
                body.tabs.forEach { tab -> key(tab.key); ids += tab.id; required(tab.label, tab.key); panel(tab.sections) }
                if (body.selected !in ids) fail(AdmissionRule.UnknownSelection, body.key.path)
                owners[body.key.path] = OwnerState(true, ids, null)
            }
        }
    }

    /** One visible panel: a tab, or a page without tabs. Status data may not overlap inside it. */
    private fun panel(sections: List<SectionContent>) {
        infos = HashSet(); data = HashSet()
        sections.forEach { section ->
            key(section.key); text(section.heading, section.key); text(section.caption, section.key)
            section.rows.forEach(::row)
        }
    }

    private fun row(row: RowContent) {
        key(row.key); setting(row.key)
        when (row) {
            is NavContent -> { required(row.title, row.key); text(row.summary, row.key); owner(row.key) }
            is LinkContent -> { required(row.title, row.key); text(row.summary, row.key); owner(row.key, row.enabled) }
            is ValueContent -> { required(row.title, row.key); required(row.value, row.key); owner(row.key, row.enabled) }
            is ChoiceContent -> {
                required(row.title, row.key); text(row.helper, row.key)
                val ids = options(row.key, row.options, row.selected)
                owner(row.key, row.enabled, ids)
                row.opens?.let { key(it.key); popups[it.key.path] = it; owner(it.key, ids = ids) }
            }
            is ToggleContent -> { required(row.title, row.key); text(row.helper, row.key); owner(row.key, row.enabled) }
            is SliderContent -> {
                required(row.title, row.key); required(row.valueLabel, row.key); text(row.helper, row.key); text(row.low, row.key); text(row.high, row.key)
                number(row.start.isFinite() && row.end.isFinite() && row.start < row.end && row.value.isFinite() && row.value in row.start..row.end &&
                    row.steps >= 0 && row.curve.isFinite() && row.curve > 0f, row.key)
                owner(row.key, row.enabled)
            }
            is ActionContent -> action(row)
            is FieldContent -> { required(row.title, row.key); text(row.error, row.key); owner(row.key, row.enabled) }
            is InfoContent -> { required(row.title, row.key); shown(row.key, row.infoKey, row.data); owner(row.key) }
            is ProgressContent -> { required(row.status, row.key); number(row.fraction?.let { it.isFinite() && it in 0f..1f } ?: true, row.key) }
            is NoticeContent -> { required(row.text, row.key); text(row.fixLabel, row.key); owner(row.key) }
            is StatusCardContent -> {
                required(row.title, row.key); number(row.progress?.let { it.isFinite() && it in 0f..1f } ?: true, row.key)
                if (row.infos.size > 2) fail(AdmissionRule.HeaderInfoCount, row.key.path)
                if (row.actions.size > 2) fail(AdmissionRule.CardActionCount, row.key.path)
                row.infos.forEach { key(it.key); shown(it.key, it.infoKey, it.data) }
                row.stats.forEach { key(it.key); required(it.label, it.key); shown(it.key, it.infoKey, it.data) }
                row.actions.forEach { key(it.key); setting(it.key); action(it); it.opens?.let(::popup) }
            }
            is LegendContent -> {
                row.entries.forEach { key(it.key); required(it.title, it.key) }
                owner(row.key, ids = row.entries.filter { it.clickable }.mapTo(HashSet()) { it.id })
            }
            is OrderedListContent -> {
                text(row.addLabel, row.key)
                row.items.forEach { key(it.key); required(it.title, it.key); text(it.subtitle, it.key) }
                owner(row.key, ids = row.items.mapTo(HashSet()) { it.id })
            }
            // Constant time: the snapshot can only exist with its certificate, so its ids are already proved unique.
            is RecordsContent -> { required(row.empty, row.key); text(row.trailingLabel, row.key); owner(row.key, records = row.snapshot) }
            is PreviewContent -> Unit
        }
        if (row !is ChoiceContent) row.opens?.let(::popup)
    }

    private fun action(row: ActionContent) {
        required(row.title, row.key); required(row.iconKey, row.key); owner(row.key, row.enabled)
    }

    private fun popup(popup: PopupContent) {
        key(popup.key); required(popup.title, popup.key); popups[popup.key.path] = popup
        when (popup) {
            is ConfirmContent -> { required(popup.message, popup.key); required(popup.confirmLabel, popup.key); required(popup.dismissLabel, popup.key); owner(popup.key) }
            is MessageContent -> { required(popup.message, popup.key); required(popup.dismissLabel, popup.key); owner(popup.key) }
            is TextEntryContent -> { required(popup.label, popup.key); required(popup.confirmLabel, popup.key); required(popup.dismissLabel, popup.key); owner(popup.key) }
            is SingleChoiceContent -> {
                required(popup.confirmLabel, popup.key); required(popup.dismissLabel, popup.key)
                owner(popup.key, ids = options(popup.key, popup.options, popup.selected))
            }
        }
    }
}

private fun admitDraft(draft: Draft): Admission {
    val admitter = Admitter(draft.page.pageKey)
    if (draft.page.pageKey.isBlank()) admitter.fail(AdmissionRule.BlankText, "page")
    admitter.body(draft.page.body)
    return if (admitter.violations.isNotEmpty()) Admission.Rejected(draft.page.pageKey, frozen(admitter.violations))
    else Admission.Accepted(AcceptedNode(draft.page, BindingTable(draft.bindings), ContentIndex(admitter.owners, admitter.popups)))
}
