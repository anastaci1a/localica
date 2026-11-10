package io.github.anastaci1a.localica.ui.spec

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Collections

/** #757 menu tokens: closed data only. Every key is an enum; pages cannot pass a size, style or colour. */
enum class MenuTextRole { PageTitle, SectionHeading, RowTitle, RowValue, RowSummary, Helper, ControlLabel, TabLabel, ButtonLabel, StatValue, StatLabel, NoticeText, FieldLabel, FieldText, PopupBody }
/** The four type sizes menus may use, with their line heights (sp). */
enum class MenuTypeSize(val sp: Int, val lineSp: Int) { Small(12, 16), Body(14, 20), Title(16, 24), Display(22, 28) }
enum class MenuWeight(val value: Int) { Regular(400), Medium(500), Bold(700) }
enum class MenuInk { Primary, Secondary, Accent, Error, Tone }
enum class MenuSize { PageInset, RowInset, LeadingBox, LeadingGap, Marker, LinkRow, ControlRow, CompactRow, LinkRowPadding, ControlRowPadding,
    HelperGap, ChevronGap, ControlGap, Chevron, SwitchWidth, SwitchHeight, Slider, SegmentPad, SegmentLabelInset, SegmentInset, TouchTarget, RowCorner, ShadowOutset }
/** The adjacency table: exactly one of these separates two neighbours in a page stream. */
enum class MenuGap { PageTop, HeadingToRow, RowToRow, RowToCaption, SectionToSection, TabsToFirst, PageBottom }
enum class MarkerTone { Positive, Caution, Negative, Neutral, Accent }
enum class MenuColor(val argb: Long) { Positive(0xFF71C9A2), Caution(0xFFE6BC64), Negative(0xFFFF7B70), Neutral(0xFF8E91A7), AccentSoft(0xFFD36BD0), AccentStrong(0xFFE040A0) }

@Immutable data class MenuTypeSpec(val size: MenuTypeSize, val weight: MenuWeight, val ink: MenuInk)

/** A dimension is a reference to a base token, or a menu-owned value; a reused base number is never copied. */
@Immutable sealed interface MenuDimension {
    data class OfSpace(val token: Space) : MenuDimension
    data class OfRadius(val token: Radius) : MenuDimension
    data class OfControl(val token: ControlSize) : MenuDimension
    data class OfClearance(val token: Clearance) : MenuDimension
    data class Own(val dp: Float) : MenuDimension
    /**
     * The paint envelope policy (I7): the [layout] size, widened to [paint] when the base's paint clearance is larger.
     * With the stock base the layout size wins and geometry is unchanged. With a wider shadow every role that derives
     * from this token moves together, so alignment holds and nothing is clipped; the appearance value is never clamped.
     */
    data class Envelope(val layout: MenuDimension, val paint: MenuDimension) : MenuDimension
}

/** A menu token proposal. Maps are copied on construction; a missing key is an admission failure, not a default. */
@Immutable class MenuSpec(type: Map<MenuTextRole, MenuTypeSpec>, size: Map<MenuSize, MenuDimension>, gap: Map<MenuGap, MenuDimension>,
    marker: Map<MarkerTone, MenuColor>) {
    val type: Map<MenuTextRole, MenuTypeSpec> = Collections.unmodifiableMap(LinkedHashMap(type))
    val size: Map<MenuSize, MenuDimension> = Collections.unmodifiableMap(LinkedHashMap(size))
    val gap: Map<MenuGap, MenuDimension> = Collections.unmodifiableMap(LinkedHashMap(gap))
    val marker: Map<MarkerTone, MenuColor> = Collections.unmodifiableMap(LinkedHashMap(marker))
    /** The stock proposal with some entries replaced. */
    fun with(type: Map<MenuTextRole, MenuTypeSpec> = emptyMap(), size: Map<MenuSize, MenuDimension> = emptyMap(),
        gap: Map<MenuGap, MenuDimension> = emptyMap(), marker: Map<MarkerTone, MenuColor> = emptyMap()) =
        MenuSpec(this.type + type, this.size + size, this.gap + gap, this.marker + marker)
    override fun equals(other: Any?) = other is MenuSpec && type == other.type && size == other.size && gap == other.gap && marker == other.marker
    override fun hashCode() = 31 * (31 * (31 * type.hashCode() + size.hashCode()) + gap.hashCode()) + marker.hashCode()
    companion object {
        /** Total: returns the accepted pair or every failed check. Nothing is clamped and nothing falls back. */
        fun admit(base: UiSpec, proposed: MenuSpec): MenuSpecResult = AcceptedMenuSpec.admit(base, proposed)
    }
}

sealed interface MenuSpecResult {
    class Accepted(val spec: AcceptedMenuSpec) : MenuSpecResult
    class Rejected(val reasons: List<String>) : MenuSpecResult
}

/** A menu proposal admitted against one base. An admission made against one base is never reused with another. */
@Immutable class AcceptedMenuSpec private constructor(val base: UiSpec, val menu: MenuSpec, private val sizes: Map<MenuSize, Dp>,
    private val gaps: Map<MenuGap, Dp>) {
    fun dp(size: MenuSize): Dp = sizes.getValue(size)
    fun dp(gap: MenuGap): Dp = gaps.getValue(gap)
    fun type(role: MenuTextRole): MenuTypeSpec = menu.type.getValue(role)
    fun color(tone: MarkerTone): MenuColor = menu.marker.getValue(tone)
    override fun equals(other: Any?) = other is AcceptedMenuSpec && base === other.base && menu == other.menu
    override fun hashCode() = 31 * System.identityHashCode(base) + menu.hashCode()

    internal companion object {
        fun admit(base: UiSpec, proposed: MenuSpec): MenuSpecResult {
            val errors = mutableListOf<String>()
            fun value(d: MenuDimension?): Dp? = when (d) {
                null -> null
                is MenuDimension.OfSpace -> base.space[d.token]
                is MenuDimension.OfRadius -> base.radius[d.token]
                is MenuDimension.OfControl -> base.control[d.token]
                is MenuDimension.OfClearance -> base.clearance[d.token]
                is MenuDimension.Own -> d.dp.dp
                is MenuDimension.Envelope -> value(d.layout)?.let { layout -> value(d.paint)?.let { paint -> maxOf(layout, paint) } }
            }
            fun resolve(name: String, d: MenuDimension?): Dp? {
                val value = value(d)
                if (value == null) errors += "$name is missing or refers to a base token the base does not define"
                else if (!value.value.isFinite() || value.value <= 0f) errors += "$name must be a positive finite size"
                return value?.takeIf { it.value.isFinite() && it.value > 0f }
            }
            val sizes = MenuSize.entries.mapNotNull { key -> resolve("size.$key", proposed.size[key])?.let { key to it } }.toMap()
            val gaps = MenuGap.entries.mapNotNull { key -> resolve("gap.$key", proposed.gap[key])?.let { key to it } }.toMap()
            MenuTextRole.entries.filter { it !in proposed.type }.forEach { errors += "type.$it is missing" }
            MarkerTone.entries.filter { it !in proposed.marker }.forEach { errors += "marker.$it is missing" }
            fun atMost(small: MenuSize, large: MenuSize, rule: String) {
                val a = sizes[small]; val b = sizes[large]
                if (a != null && b != null && a > b) errors += "$rule: size.$small (${a.value}) exceeds size.$large (${b.value})"
            }
            listOf(MenuSize.LinkRow, MenuSize.ControlRow, MenuSize.CompactRow).forEach { atMost(MenuSize.TouchTarget, it, "touch minimum fits every row minimum") }
            atMost(MenuSize.Marker, MenuSize.LeadingBox, "marker fits the leading box")
            atMost(MenuSize.ShadowOutset, MenuSize.PageInset, "shadow outset fits the page inset")
            // The stream's clipping ancestor is the scroll container: its edge gaps must hold the same paint (I7).
            listOf(MenuGap.PageTop, MenuGap.TabsToFirst, MenuGap.PageBottom).forEach { edge ->
                val outset = sizes[MenuSize.ShadowOutset]; val gap = gaps[edge]
                if (outset != null && gap != null && outset > gap) errors += "shadow outset fits the stream edge: size.ShadowOutset (${outset.value}) exceeds gap.$edge (${gap.value})"
            }
            return if (errors.isEmpty()) MenuSpecResult.Accepted(AcceptedMenuSpec(base, proposed,
                Collections.unmodifiableMap(LinkedHashMap(sizes)), Collections.unmodifiableMap(LinkedHashMap(gaps))))
            else MenuSpecResult.Rejected(errors.toList())
        }
    }
}

private fun type(size: MenuTypeSize, weight: MenuWeight, ink: MenuInk) = MenuTypeSpec(size, weight, ink)
/** One shared envelope for the page inset and the stream's clipped edges: the section space, or the shadow clearance when that is larger. */
private val PaintEnvelope = MenuDimension.Envelope(MenuDimension.OfSpace(Space.Section), MenuDimension.OfClearance(Clearance.ShadowInset))

/** Stock menu tokens (DESIGN 5.1 to 5.4). ButtonLabel is pinned from the action button's label style (14/20 Medium). */
val StockMenuSpec: MenuSpec = MenuSpec(
    type = mapOf(
        MenuTextRole.PageTitle to type(MenuTypeSize.Display, MenuWeight.Bold, MenuInk.Primary),
        MenuTextRole.SectionHeading to type(MenuTypeSize.Body, MenuWeight.Medium, MenuInk.Primary),
        MenuTextRole.RowTitle to type(MenuTypeSize.Title, MenuWeight.Regular, MenuInk.Primary),
        MenuTextRole.RowValue to type(MenuTypeSize.Title, MenuWeight.Regular, MenuInk.Accent),
        MenuTextRole.RowSummary to type(MenuTypeSize.Body, MenuWeight.Regular, MenuInk.Secondary),
        MenuTextRole.Helper to type(MenuTypeSize.Small, MenuWeight.Regular, MenuInk.Secondary),
        MenuTextRole.ControlLabel to type(MenuTypeSize.Title, MenuWeight.Regular, MenuInk.Primary),
        MenuTextRole.TabLabel to type(MenuTypeSize.Title, MenuWeight.Medium, MenuInk.Primary),
        MenuTextRole.ButtonLabel to type(MenuTypeSize.Body, MenuWeight.Medium, MenuInk.Primary),
        MenuTextRole.StatValue to type(MenuTypeSize.Display, MenuWeight.Bold, MenuInk.Primary),
        MenuTextRole.StatLabel to type(MenuTypeSize.Small, MenuWeight.Regular, MenuInk.Secondary),
        MenuTextRole.NoticeText to type(MenuTypeSize.Body, MenuWeight.Regular, MenuInk.Tone),
        MenuTextRole.FieldLabel to type(MenuTypeSize.Small, MenuWeight.Regular, MenuInk.Secondary),
        MenuTextRole.FieldText to type(MenuTypeSize.Title, MenuWeight.Regular, MenuInk.Primary),
        MenuTextRole.PopupBody to type(MenuTypeSize.Body, MenuWeight.Regular, MenuInk.Primary)),
    size = mapOf(
        MenuSize.PageInset to PaintEnvelope,
        MenuSize.RowInset to MenuDimension.OfSpace(Space.Comfortable),
        MenuSize.LeadingBox to MenuDimension.OfControl(ControlSize.SettingsIcon),
        MenuSize.LeadingGap to MenuDimension.OfSpace(Space.Comfortable),
        MenuSize.Marker to MenuDimension.Own(10f),
        MenuSize.LinkRow to MenuDimension.Own(64f),
        MenuSize.ControlRow to MenuDimension.OfControl(ControlSize.PreferenceRow),
        MenuSize.CompactRow to MenuDimension.OfControl(ControlSize.PreferenceCompact),
        MenuSize.LinkRowPadding to MenuDimension.OfSpace(Space.Compact),
        MenuSize.ControlRowPadding to MenuDimension.OfSpace(Space.Tight),
        MenuSize.HelperGap to MenuDimension.OfSpace(Space.Tight),
        MenuSize.ChevronGap to MenuDimension.OfSpace(Space.Comfortable),
        MenuSize.ControlGap to MenuDimension.OfSpace(Space.Base),
        MenuSize.Chevron to MenuDimension.Own(20f),
        MenuSize.SwitchWidth to MenuDimension.Own(60f),
        MenuSize.SwitchHeight to MenuDimension.Own(48f),
        MenuSize.Slider to MenuDimension.Own(48f),
        MenuSize.SegmentPad to MenuDimension.Own(14f),
        MenuSize.SegmentLabelInset to MenuDimension.OfSpace(Space.Base),
        // The lens inset around the cells of the segmented control (today ChoiceInnerInset).
        MenuSize.SegmentInset to MenuDimension.OfSpace(Space.Tight),
        MenuSize.TouchTarget to MenuDimension.Own(48f),
        MenuSize.RowCorner to MenuDimension.OfRadius(Radius.Menu),
        MenuSize.ShadowOutset to MenuDimension.OfClearance(Clearance.ShadowInset)),
    gap = mapOf(
        MenuGap.PageTop to PaintEnvelope,
        MenuGap.HeadingToRow to MenuDimension.OfSpace(Space.Base),
        MenuGap.RowToRow to MenuDimension.OfSpace(Space.Compact),
        MenuGap.RowToCaption to MenuDimension.OfSpace(Space.Compact),
        MenuGap.SectionToSection to MenuDimension.OfSpace(Space.Section),
        MenuGap.TabsToFirst to PaintEnvelope,
        MenuGap.PageBottom to PaintEnvelope),
    marker = mapOf(MarkerTone.Positive to MenuColor.Positive, MarkerTone.Caution to MenuColor.Caution, MarkerTone.Negative to MenuColor.Negative,
        MarkerTone.Neutral to MenuColor.Neutral, MarkerTone.Accent to MenuColor.AccentSoft))
