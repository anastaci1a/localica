package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** U07.00 (D39–D43, #642 and the 2026-11-07 amendments): one typed popup state per key, admission at the intent, a shared element that follows the choice, and no contact API in the scope. */
class PopupApiTest {
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private val basis=ExpansionBasis(Rect(40f,100f,88f,148f),Rect(16f,100f,296f,420f),2.75f)
    private fun node(h: EngineHarness,key: Any)=SurfaceNode(h.clock,key,PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(40f,100f,88f,148f,CornerRadius(24f))),JoinPolicy.Union),key to "shape"),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),ShadowPlane(h.clock,HostKey.next(Any())))
    private fun state(h: EngineHarness,key: Any="menu")=PopupController(key,PopupMotion(h.clock,geometry.expansion,key))
    private fun scope(state: PopupController,model: PopupModel)=PopupHostScope(state,model)

    @Test fun sameKeyReclaimsStateAndRejectsUnboundPhysicalHome() = EngineHarness(spec=geometry).use { h ->
        val popup=state(h); val home=HomeRef("row",HomeKind.Surface)
        // D43: an unbound physical home is a programmer error at the intent, rejected before any state changes.
        assertFalse(home.available); assertThrows(IllegalStateException::class.java) { popup.open(home) }
        assertFalse(popup.visible); assertNull(popup.home); assertEquals(0L,popup.generation)
        val trigger=node(h,"row"); val host=HostKey.next(Any()); home.bind(trigger,host); assertTrue(home.available); assertSame(trigger,home.node); assertSame(host,home.host)
        // Stock origins need no physical binding.
        assertTrue(HomeRef("settings",HomeKind.Detached).available && HomeRef("page",HomeKind.Page).available)
        popup.open(home); assertTrue(popup.visible); assertSame(home,popup.home); val opened=popup.generation
        // Equal open on the active home is idempotent.
        popup.open(home); assertEquals(opened,popup.generation)
        // The basis arrives with the presenter's measurement; the pending intent applies there.
        popup.motion.place(basis); h.advance(.05); val phase=popup.expansion.phase; assertTrue(phase.value() in 0.001f..0.999f && phase.target==1f)
        // Dismiss during opening and reopen during retract retarget the same expansion from its current kinematics (one writer, C0/C1).
        popup.dismiss(); assertFalse(popup.visible); h.advance(.02)
        val before=phase.sampleAt(h.clock.stampNow); popup.open(home); val after=phase.sampleAt(h.clock.stampNow)
        assertSame(phase,popup.expansion.phase); assertEquals(1f,phase.target); assertEquals(before.value,after.value,1e-5f); assertEquals(before.velocity,after.velocity,1e-3f*maxOf(1f,abs(before.velocity)))
        assertTrue(popup.generation>opened)
        // Only the bound node unbinds; return keeps the last valid binding while new openings are refused.
        assertThrows(IllegalArgumentException::class.java) { home.unbind(node(h,"other")) }
        home.unbind(trigger); assertFalse(home.available); assertSame(trigger,home.node)
        val reopen=popup.generation; popup.dismiss(); assertThrows(IllegalStateException::class.java) { popup.open(home) }; assertFalse(popup.visible); assertEquals(reopen+1,popup.generation)
        // A dismissed (retained) trigger is not a valid home for a new opening either.
        val next=node(h,"row2"); home.bind(next,host); next.dismiss(); assertFalse(home.available)
        h.advance(2.0); assertEquals(0f,phase.value(),0f); assertTrue(phase.settled)
    }

    @Test fun choiceMenusAreTitlelessAndSelectionSwapsTheSharedElement() = EngineHarness(spec=geometry).use { h ->
        // #642: a single-select picker is a titleless text list that applies on tap (no Save/Cancel footer).
        assertThrows(IllegalArgumentException::class.java) { PopupModel("extra",PopupVariant.Choice,title="Extra information") }
        assertThrows(IllegalArgumentException::class.java) { PopupModel("extra",PopupVariant.Choice,footer={}) }
        var allow=false; val model=PopupModel("extra",PopupVariant.Choice,onDismissRequest={ allow })
        val popup=state(h,"extra"); val home=HomeRef("extra",HomeKind.Surface); home.bind(node(h,"extra"),HostKey.next(Any()))
        // Opening: the trigger's preview text is the selected row, carried out as the shared element.
        popup.open(home,shared="Album"); assertEquals("Album",popup.shared); val s=scope(popup,model); assertTrue(s.interactive)
        // A refused request leaves the popup, its shared element and its generation untouched.
        val g=popup.generation; s.select("Year"); assertTrue(popup.visible); assertEquals("Album",popup.shared); assertEquals(g,popup.generation)
        // Choosing: the chosen row becomes the shared element and closes into the trigger; the old row is ordinary content.
        allow=true; s.select("Year"); assertFalse(popup.visible); assertEquals("Year",popup.shared); assertFalse(s.interactive)
        assertThrows(IllegalStateException::class.java) { popup.select("Genre") }
        // Stale scopes from an earlier opening cannot act on a reopened popup.
        popup.open(home,shared="Year"); assertFalse(s.interactive); s.dismiss(); s.select("Genre"); assertTrue(popup.visible); assertEquals("Year",popup.shared)
        val fresh=scope(popup,model); assertTrue(fresh.interactive); fresh.dismiss(); assertFalse(popup.visible); assertEquals("Year",popup.shared)
        // Programmatic dismiss closes directly, without the user-intent policy.
        allow=false; popup.open(home); popup.dismiss(); assertFalse(popup.visible)
    }

    @Test fun scrubToSelectOnlyForControlAnchoredMenus() = EngineHarness(spec=geometry).use { h ->
        val scrubbing=mutableListOf<Pair<PopupVariant,HomeKind>>()
        for(variant in PopupVariant.entries) for(kind in HomeKind.entries) {
            val popup=state(h,variant to kind); val home=HomeRef(kind,kind)
            if(kind!=HomeKind.Detached && kind!=HomeKind.Page) home.bind(node(h,variant to kind),HostKey.next(Any()))
            popup.open(home); if(scope(popup,PopupModel(variant to kind,variant)).scrubs) scrubbing.add(variant to kind)
        }
        val control=listOf(HomeKind.Surface,HomeKind.Quiet,HomeKind.Contextual)
        assertEquals(listOf(PopupVariant.Menu,PopupVariant.Choice).flatMap { v -> control.map { v to it } },scrubbing)
    }

    @Test fun scopeAndStateExposeNoContactOrMutableExpansion() {
        // D42: popups are programmatic in Stage 3; the state lends a read-only ExpansionView and the scope has no contact API.
        assertEquals(ExpansionView::class.java,PopupState::class.java.getMethod("getExpansion").returnType)
        val names=(PopupScope::class.java.methods+PopupState::class.java.methods).map { it.name }
        assertTrue(names.none { it.startsWith("contact") || it=="setBasis" || it=="target" || it=="bind" })
        assertEquals(setOf("getInteractive","getScrubs","dismiss","select"),PopupScope::class.java.declaredMethods.map { it.name }.toSet())
    }
}
