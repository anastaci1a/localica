package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.RuleChain

/** Player gesture ownership may update effects and input, while settled browse rows stay quiet. */
class BrowsePlayerCompositionTest {
    private val compose = localicaComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(BrowsePreferenceIsolation()).around(compose)

    @Test fun stableEventsUseAppliedTargetsAndRejectAbandonedTargets() {
        var observed = ""
        val owner = CurrentEvent<String> { observed = "old:$it" }
        val event = owner.callback
        val pending = Snapshot.takeMutableSnapshot()
        try {
            pending.enter { owner.update { observed = "new:$it" } }
            event("before")
            assertEquals("old:before",observed)
            pending.apply().check()
            event("after")
            assertEquals("new:after",observed)
        } finally { pending.dispose() }
        val abandoned = Snapshot.takeMutableSnapshot()
        try { abandoned.enter { owner.update { observed = "abandoned:$it" } } }
        finally { abandoned.dispose() }
        event("retained")
        assertEquals("new:retained",observed)
        assertSame(event,owner.callback)

        var version by mutableIntStateOf(0)
        var callback: (() -> Unit)? = null
        compose.setContent {
            val current = version
            val stable = rememberCurrentAction { observed = "ui:$current" }
            SideEffect { callback=stable }
        }
        compose.waitForIdle()
        val retained = requireNotNull(callback)
        compose.runOnIdle { retained();assertEquals("ui:0",observed);version=1 }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(retained,callback);retained();assertEquals("ui:1",observed) }
    }

    @Test fun playerGesturesDoNotRecomposeRowsOrChangeTheirPixels() {
        val tracks = (0..6).map { Track("quiet:$it", "Quiet song $it", fileName="$it.mp3", relativePath="$it.mp3") }
        val library = LibraryUiState(LibrarySnapshot(folders=listOf(FolderSelection("root","Music")),tracks=tracks),loading=false)
        val playlists = PlaylistState(loading=false)
        val route = BrowseRoute(MainTab.Songs)
        val work = mutableListOf<String>()
        val observer: (String,Any) -> Unit = { kind,_ -> work += kind }
        var open by mutableStateOf(false)
        var reference by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseCellWorkObserver provides observer,
                LocalGlassAppearance provides GlassAppearance(renderer=GlassRendererMode.None)) {
                key(reference) {
                if (reference) ReferenceBrowserScreenP1(library,playlists,route,{},{},{},{_,_,_->},null,null,true,open,
                    reducedMotion=true,queryDispatcher=kotlinx.coroutines.Dispatchers.Unconfined)
                else BrowserScreen(library,playlists,route,{},{},{},{_,_,_->},null,null,true,open,
                    reducedMotion=true,queryDispatcher=kotlinx.coroutines.Dispatchers.Unconfined)
                }
            }
        } }
        compose.onNodeWithText("Quiet song 0").assertIsDisplayed()
        compose.waitForIdle()
        fun pixels(): IntArray {
            val image=compose.onNodeWithTag("browser-list").captureToImage().toPixelMap()
            return IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() }
        }
        val original=pixels()
        compose.runOnIdle { assertTrue("Fixture must observe actual row composition",work.count { it=="row" }>0);work.clear() }
        for(value in listOf(true,false,true,false)) {
            compose.runOnIdle { open=value }
            compose.waitForIdle()
            assertArrayEquals("Retained browse pixels at playerOpen=$value",original,pixels())
        }
        compose.runOnIdle {
            assertEquals("Browser observes exactly four gesture ownership changes",4,work.count { it=="screen" })
            assertEquals("Player state must not recompose a browse row",0,work.count { it=="row" })
        }
        compose.runOnIdle { reference=true }
        compose.onNodeWithText("Quiet song 0").assertIsDisplayed()
        assertArrayEquals("Candidate matches frozen edcead93 browse pixels",pixels(),original)
        compose.runOnIdle { work.clear() }
        for (value in listOf(true,false,true,false)) {
            compose.runOnIdle { open=value }
            compose.waitForIdle()
            assertArrayEquals("Frozen baseline pixels at playerOpen=$value",original,pixels())
        }
        compose.runOnIdle {
            assertTrue("Baseline negative control must detect the original repeated row composition",work.count { it=="row" }>0)
        }
    }
}
