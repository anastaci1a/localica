package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import org.junit.Assert.*
import org.junit.Test

/** #757 M0: menu tokens are closed data admitted against a base; an invalid override is rejected, never clamped. */
class MenuSpecAdmissionTest {
    private fun accepted(base: UiSpec = StockUiSpec, menu: MenuSpec = StockMenuSpec) =
        (MenuSpec.admit(base, menu) as MenuSpecResult.Accepted).spec
    private fun rejected(base: UiSpec = StockUiSpec, menu: MenuSpec) = (MenuSpec.admit(base, menu) as MenuSpecResult.Rejected).reasons

    @Test fun stockSpecIsAcceptedWithTheDesignValues() {
        val spec = accepted()
        val sizes = mapOf(MenuSize.PageInset to 24f, MenuSize.RowInset to 16f, MenuSize.LeadingBox to 24f, MenuSize.LeadingGap to 16f,
            MenuSize.Marker to 10f, MenuSize.LinkRow to 64f, MenuSize.ControlRow to 56f, MenuSize.CompactRow to 48f, MenuSize.LinkRowPadding to 8f,
            MenuSize.ControlRowPadding to 4f, MenuSize.HelperGap to 4f, MenuSize.ChevronGap to 16f, MenuSize.ControlGap to 12f, MenuSize.Chevron to 20f,
            MenuSize.SwitchWidth to 60f, MenuSize.SwitchHeight to 48f, MenuSize.Slider to 48f, MenuSize.SegmentPad to 14f,
            MenuSize.SegmentLabelInset to 12f, MenuSize.SegmentInset to 4f, MenuSize.TouchTarget to 48f, MenuSize.RowCorner to 20f, MenuSize.ShadowOutset to 22.8f)
        assertEquals(MenuSize.entries.toSet(), sizes.keys)
        sizes.forEach { (key, value) -> assertEquals("size.$key", value, spec.dp(key).value, 0f) }
        val gaps = mapOf(MenuGap.PageTop to 24f, MenuGap.HeadingToRow to 12f, MenuGap.RowToRow to 8f, MenuGap.RowToCaption to 8f,
            MenuGap.SectionToSection to 24f, MenuGap.TabsToFirst to 24f, MenuGap.PageBottom to 24f)
        assertEquals(MenuGap.entries.toSet(), gaps.keys)
        gaps.forEach { (key, value) -> assertEquals("gap.$key", value, spec.dp(key).value, 0f) }
        assertEquals(setOf(12, 14, 16, 22), MenuTextRole.entries.map { spec.type(it).size.sp }.toSet())
        assertEquals(MenuTypeSpec(MenuTypeSize.Small, MenuWeight.Regular, MenuInk.Secondary), spec.type(MenuTextRole.Helper))
        assertEquals(0xFF8E91A7, spec.color(MarkerTone.Neutral).argb)
    }

    @Test fun reusedEntriesFollowTheBaseInsteadOfCopyingItsNumber() {
        val base = (uiSpec(InterfaceSettings(), UiSpecData(space = mapOf(Space.Section to 26.dp))) as SpecResult.Accepted).spec
        val spec = accepted(base)
        assertEquals(26f, spec.dp(MenuSize.PageInset).value, 0f)
        assertEquals(26f, spec.dp(MenuGap.SectionToSection).value, 0f)
        assertEquals(64f, spec.dp(MenuSize.LinkRow).value, 0f)
        assertNotEquals("an admission belongs to its base", accepted(), spec)
    }

    @Test fun invalidOverridesAreRejectedWithEveryFailedCheckAndNothingIsClamped() {
        val reasons = rejected(menu = StockMenuSpec.with(size = mapOf(MenuSize.Marker to MenuDimension.Own(30f),
            MenuSize.TouchTarget to MenuDimension.Own(60f), MenuSize.Chevron to MenuDimension.Own(-1f))))
        assertTrue(reasons.toString(), reasons.any { "marker fits the leading box" in it })
        assertEquals(reasons.toString(), 2, reasons.count { "touch minimum fits every row minimum" in it })
        assertTrue(reasons.toString(), reasons.any { "size.Chevron must be a positive finite size" in it })
        assertEquals(4, reasons.size)
    }

    @Test fun nonFiniteAndZeroSizesAreRejected() {
        for (bad in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(listOf("gap.RowToRow must be a positive finite size"), rejected(menu = StockMenuSpec.with(gap = mapOf(MenuGap.RowToRow to MenuDimension.Own(bad)))))
    }

    @Test fun aMissingEntryOrAnUndefinedBaseTokenIsRejectedNotDefaulted() {
        val missing = MenuSpec(StockMenuSpec.type - MenuTextRole.Helper, StockMenuSpec.size - MenuSize.Marker, StockMenuSpec.gap, StockMenuSpec.marker - MarkerTone.Accent)
        assertEquals(setOf("size.Marker is missing or refers to a base token the base does not define", "type.Helper is missing", "marker.Accent is missing"),
            rejected(menu = missing).toSet())
        assertEquals(listOf("size.RowCorner is missing or refers to a base token the base does not define"),
            rejected(menu = StockMenuSpec.with(size = mapOf(MenuSize.RowCorner to MenuDimension.OfRadius(Radius.Pill)))))
    }

    /** agent-19 M0 ruling 2: a caller proposal whose inset cannot hold the base's shadow is still rejected, never clamped. */
    @Test fun aProposalWhoseInsetCannotHoldTheShadowIsRejected() {
        val wide = (uiSpec(InterfaceSettings(glass = GlassAppearance(shadowSpread = 1f))) as SpecResult.Accepted).spec
        assertTrue(wide.clearance.getValue(Clearance.ShadowInset) > 24.dp)
        val fixed = StockMenuSpec.with(size = mapOf(MenuSize.PageInset to MenuDimension.OfSpace(Space.Section)),
            gap = mapOf(MenuGap.PageTop to MenuDimension.OfSpace(Space.Section)))
        val reasons = rejected(wide, fixed)
        assertEquals(1, reasons.count { "shadow outset fits the page inset" in it })
        assertEquals(1, reasons.count { "shadow outset fits the stream edge" in it && "PageTop" in it })
    }

    /** The default join admits every accepted shadow spread: the shared envelope widens, and stock geometry is unchanged. */
    @Test fun theStockMenuIsAdmittedOverTheWholeAcceptedShadowDomainThroughOneEnvelope() {
        val edges = listOf(MenuGap.PageTop, MenuGap.TabsToFirst, MenuGap.PageBottom)
        for (step in 0..20) {
            val spread = step / 20f
            val base = (uiSpec(InterfaceSettings(glass = GlassAppearance(shadowSpread = spread))) as SpecResult.Accepted).spec
            val spec = (MenuSpec.admit(base, StockMenuSpec) as MenuSpecResult.Accepted).spec
            val outset = base.clearance.getValue(Clearance.ShadowInset)
            val expected = maxOf(base.space.getValue(Space.Section), outset)
            assertEquals("spread $spread", outset, spec.dp(MenuSize.ShadowOutset))
            assertEquals("spread $spread", expected, spec.dp(MenuSize.PageInset))
            edges.forEach { assertEquals("spread $spread $it", expected, spec.dp(it)) }
            // Only the envelope moves: every other size and gap is the stock value.
            val stock = (MenuSpec.admit(StockUiSpec, StockMenuSpec) as MenuSpecResult.Accepted).spec
            MenuSize.entries.minus(listOf(MenuSize.PageInset, MenuSize.ShadowOutset)).forEach { assertEquals("spread $spread $it", stock.dp(it), spec.dp(it)) }
            MenuGap.entries.minus(edges).forEach { assertEquals("spread $spread $it", stock.dp(it), spec.dp(it)) }
        }
        val stock = (MenuSpec.admit(StockUiSpec, StockMenuSpec) as MenuSpecResult.Accepted).spec
        assertEquals(24.dp, stock.dp(MenuSize.PageInset)); edges.forEach { assertEquals(24.dp, stock.dp(it)) }
    }

    @Test fun aProposalCopiesItsMaps() {
        val sizes = StockMenuSpec.size.toMutableMap()
        val menu = MenuSpec(StockMenuSpec.type, sizes, StockMenuSpec.gap, StockMenuSpec.marker)
        sizes[MenuSize.Marker] = MenuDimension.Own(99f)
        assertEquals(MenuDimension.Own(10f), menu.size[MenuSize.Marker])
        assertEquals(StockMenuSpec, menu)
    }
}
