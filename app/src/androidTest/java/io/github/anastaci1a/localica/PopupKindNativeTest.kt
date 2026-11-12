package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.SharedContentKind
import io.github.anastaci1a.localica.ui.components.TriggerRole
import io.github.anastaci1a.localica.ui.components.rememberPopupSharedContent
import io.github.anastaci1a.localica.ui.engine.LocalPopupKind
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.PopupMotionVariant
import io.github.anastaci1a.localica.ui.spec.StockPopupMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #854 review F1/F7, native proof set: a real popup runs the kind derived from its placement and trigger role (read where it
 * applies, in the popup's own content), a popup first composed visible before its trigger is measured keeps one kind and owner
 * for that presentation, and shared content runs a centred popup's kind. */
class PopupKindNativeTest {
    @get:Rule val compose = createComposeRule()
    private val measured = Rect(40f, 1600f, 140f, 1664f)

    private class Scene { var open by mutableStateOf(false); val seen = mutableListOf<PopupKind>() }

    private fun menu(anchor: GlassAnchor, scene: Scene) {
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                GlassMenu(scene.open, anchor, { scene.open = false }) {
                    val kind = LocalPopupKind.current
                    SideEffect { scene.seen += kind }
                    Text("Kind", Modifier.testTag("kind-content"))
                }
            }
        } }
    }

    private fun present(scene: Scene): List<PopupKind> {
        scene.seen.clear(); compose.runOnIdle { scene.open = true }
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        return compose.runOnIdle { scene.seen.toList() }
    }

    private fun close(scene: Scene) {
        compose.runOnIdle { scene.open = false }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("kind-content").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    @Test fun eachTriggerRoleOpensItsDerivedKind() {
        for ((role, contextual, expected) in listOf(Triple(TriggerRole.Surface, false, PopupKind.Menu), Triple(TriggerRole.Navigation, false, PopupKind.BottomMenu),
                Triple(TriggerRole.PlainIcon, false, PopupKind.Menu), Triple(TriggerRole.Surface, true, PopupKind.RowMenu), Triple(TriggerRole.Navigation, true, PopupKind.RowMenu))) {
            val anchor = GlassAnchor().apply { bounds = measured; bindRole(role); this.contextual = contextual }
            val scene = Scene()
            menu(anchor, scene)
            val kinds = present(scene)
            assertTrue("$role contextual=$contextual: $kinds", kinds.isNotEmpty() && kinds.all { it == expected })
            if (expected == PopupKind.BottomMenu) assertEquals(PopupMotionVariant.CrossAxisSnap, StockPopupMotion.variants[expected])
            close(scene)
        }
    }

    @Test fun aMenuFirstComposedVisibleKeepsItsKindUntilClosedRestThenReopensAsItsTrigger() {
        val anchor = GlassAnchor()
        val scene = Scene().apply { open = true }
        menu(anchor, scene)
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        // The trigger is measured and its navigation role binds while the first presentation is in flight.
        compose.runOnIdle { anchor.bounds = measured; anchor.bindRole(TriggerRole.Navigation) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        val first = compose.runOnIdle { scene.seen.toList() }
        assertTrue("one kind per presentation: $first", first.isNotEmpty() && first.all { it == PopupKind.Dialog })
        close(scene)
        val reopened = present(scene)
        assertTrue("the settled kind applies from the next presentation: $reopened", reopened.isNotEmpty() && reopened.all { it == PopupKind.BottomMenu })
        close(scene)
    }

    @Test fun sharedContentRunsTheCentredPopupsKind() {
        val anchor = GlassAnchor().apply { bounds = measured }
        val scene = Scene()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                // As the queue selector: the shared title is composed in its trigger and in the dialog.
                val shared = rememberPopupSharedContent(anchor) { Text("Title") }
                shared.Compact()
                GlassMorphPopup(scene.open, anchor, { scene.open = false }, GlassPlacement.Center, sharedContent = shared) {
                    val kind = LocalPopupKind.current
                    SideEffect { scene.seen += kind }
                    shared.Expanded()
                    Text("Kind", Modifier.testTag("kind-content"))
                }
            }
        } }
        val kinds = present(scene)
        assertTrue("$kinds", kinds.isNotEmpty() && kinds.all { it == SharedContentKind })
        close(scene)
    }
}
