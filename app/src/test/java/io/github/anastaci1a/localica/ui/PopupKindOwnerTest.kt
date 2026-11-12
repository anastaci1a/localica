package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.components.SharedContentKind
import io.github.anastaci1a.localica.ui.components.TriggerRole
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #854 review F1/F7: the one kind derivation (placement x trigger role), the owner it selects and the shared-content guard. */
class PopupKindOwnerTest {
    private val geometry = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 2000f, 1f / 60)) as SpecResult.Accepted).spec
    private val roles = listOf(TriggerRole.Surface, TriggerRole.PlainIcon, TriggerRole.Contextual, TriggerRole.Navigation,
        TriggerRole.BrowseOptions, TriggerRole.Selection, TriggerRole.Lyrics, TriggerRole.SelectionItem(1),
        TriggerRole.MaterialSurface(1), TriggerRole.RetainedSurface(1), TriggerRole.LyricsHeader(1, 1, false))

    private fun anchor(role: TriggerRole<*, *>, contextual: Boolean, measured: Boolean = true) = GlassAnchor().apply {
        bindRole(role); this.contextual = contextual; if (measured) bounds = Rect(10f, 2000f, 110f, 2064f)
    }

    @Test fun kindFollowsPlacementAndTriggerRole() {
        for (role in roles) for (contextual in listOf(false, true)) {
            val anchor = anchor(role, contextual)
            val menu = if (contextual) PopupKind.RowMenu else if (role == TriggerRole.Navigation) PopupKind.BottomMenu else PopupKind.Menu
            assertEquals("$role contextual=$contextual", menu, GlassPlacement.Menu.popupKind(anchor))
            assertEquals(PopupKind.Dialog, GlassPlacement.Center.popupKind(anchor))
            for (legacy in listOf(GlassPlacement.Search, GlassPlacement.Page, GlassPlacement.Sheet))
                assertEquals("$legacy $role", PopupKind.Legacy, legacy.popupKind(anchor))
        }
        // The navigation chrome's More menu is the bottom menu (CrossAxisSnap); every other menu keeps its own law.
        assertEquals(PopupMotionVariant.CrossAxisSnap, StockPopupMotion.variants[GlassPlacement.Menu.popupKind(anchor(TriggerRole.Navigation, false))])
        assertNotEquals(PopupMotionVariant.CrossAxisSnap, StockPopupMotion.variants[GlassPlacement.Menu.popupKind(anchor(TriggerRole.Surface, false))])
    }

    @Test fun aMenuIsCentredUntilItsTriggerIsMeasured() {
        for (role in roles) {
            val anchor = anchor(role, false, measured = false)
            assertEquals(GlassPlacement.Center, anchor.menuPlacement())
            assertEquals(PopupKind.Dialog, anchor.menuPlacement().popupKind(anchor))
            anchor.bounds = Rect(0f, 0f, 48f, 48f)
            assertEquals(GlassPlacement.Menu, anchor.menuPlacement())
        }
    }

    @Test fun sharedContentRunsOnlyACentredPopupsKind() {
        for (role in roles) for (contextual in listOf(false, true)) {
            val anchor = anchor(role, contextual)
            assertEquals("$role", SharedContentKind, GlassPlacement.Center.popupKind(anchor))
            for (placement in GlassPlacement.entries - GlassPlacement.Center)
                assertNotEquals("the guard rejects shared content in $placement", SharedContentKind, placement.popupKind(anchor))
        }
    }

    /** GlassMorphPopup's composition, reduced to its owner rule: remember(clock, key, settled kind), the latch committed in SideEffect. */
    private class Host(private val h: EngineHarness, private val spec: UiSpec) {
        private var latch: PopupKindLatch? = null
        var owner: PopupWindowMotion? = null; private set
        val disposed = mutableListOf<PopupWindowMotion>()
        fun compose(derived: PopupKind, present: Boolean): PopupWindowMotion {
            val latch = latch ?: PopupKindLatch(derived).also { latch = it }
            val kind = latch.settle(derived, present)
            val current = owner?.takeIf { it.kind == kind } ?: PopupWindowMotion(h.clock, spec.expansion, "menu", false, false, kind)
                .also { replacement -> owner?.let { it.dispose(); disposed += it }; owner = replacement }
            latch.commit(kind)
            return current
        }
    }

    /** F1: a menu first composed visible before its trigger is measured derives Dialog, then Menu after the first placement, while
     * its opening is in flight. The owner that the gate adapter and window attachment captured stays the live one to the close. */
    @Test fun aKindThatSettlesWhileOpenKeepsThePresentOwnerUntilClosedRest() = EngineHarness(spec = geometry).use { h ->
        val host = Host(h, geometry)
        val anchor = anchor(TriggerRole.Navigation, false, measured = false)
        val captured = host.compose(anchor.menuPlacement().popupKind(anchor), present = false)
        assertEquals(PopupKind.Dialog, captured.kind)
        // The adapter's first run: present, show, place, native admission.
        captured.show(true); captured.place(Rect(0f, 0f, 48f, 48f), Rect(20f, 30f, 400f, 600f), 1f)
        captured.expansion.acknowledgeNativeGate(captured.expansion.nativeGeneration)
        h.advance(.05)
        anchor.bounds = Rect(10f, 2000f, 110f, 2064f)
        assertEquals(PopupKind.BottomMenu, anchor.menuPlacement().popupKind(anchor))
        for (frame in 0 until 3) {
            assertSame("no new owner while present", captured, host.compose(anchor.menuPlacement().popupKind(anchor), present = true))
            h.advance(.05)
        }
        assertTrue("the captured owner is never disposed while present", host.disposed.isEmpty())
        h.advance(2.0)
        assertTrue("the captured owner reaches open", captured.expansion.expanded && captured.expansion.phase.settled)
        captured.show(false); h.advance(2.0)
        // Closed rest: the settled kind applies from the next presentation, as a new owner.
        val next = host.compose(anchor.menuPlacement().popupKind(anchor), present = false)
        assertNotSame(captured, next)
        assertEquals(PopupKind.BottomMenu, next.kind)
        assertEquals(listOf(captured), host.disposed)
        next.dispose()
    }

    @Test fun theLatchChangesOnlyWhileNotPresent() {
        val latch = PopupKindLatch(PopupKind.Menu)
        assertEquals(PopupKind.BottomMenu, latch.settle(PopupKind.BottomMenu, present = false))
        assertEquals("uncommitted kinds never leak", PopupKind.Menu, latch.settle(PopupKind.Dialog, present = true))
        latch.commit(PopupKind.BottomMenu)
        for (derived in PopupKind.entries) assertEquals(PopupKind.BottomMenu, latch.settle(derived, present = true))
    }

    /** Source contract (checklist 48): GlassMorphPopup keys its owner on the settled kind, and the effects that use the owner
     * (the native gate adapter and the window attachment) are keyed on it. */
    @Test fun glassMorphPopupKeysItsOwnerAndItsEffectsOnTheSettledKind() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt").readText()
        val popup = source.substringAfter("internal fun GlassMorphPopup(").substringBefore("\n@Composable\ninternal fun ")
        assertTrue(Regex("""val popupKind = settledPopupKind\(anchor, placement\.popupKind\(anchor\), present\)""").containsMatchIn(popup))
        assertTrue("present is declared before the kind", popup.indexOf("var present by remember") in 0 until popup.indexOf("settledPopupKind("))
        assertTrue(Regex("""rememberPopupWindowMotion\(anchor, [^\n]*, popupKind\)""").containsMatchIn(popup))
        assertTrue("the native gate adapter restarts with its owner", Regex("""LaunchedEffect\(motion, visible, """).containsMatchIn(popup))
        assertEquals(1, Regex("""motion\.attached\(""").findAll(popup).count())
        assertTrue("attachment follows the owner", Regex("""DisposableEffect\(window, motion\) \{\s*window\?\.let \{ motion\.attached\(""").containsMatchIn(popup))
    }
}
