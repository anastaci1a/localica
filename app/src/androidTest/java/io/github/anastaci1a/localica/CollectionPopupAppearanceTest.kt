package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real collection callers, with prepared artwork rather than a forced dark app theme. */
class CollectionPopupAppearanceTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(bright: Boolean) {
        val track = Track("appearance:$bright", "Fixture song", album = "Fixture album", artist = "Fixture artist",
            durationMs = 0L, fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = listOf(track))
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), album.title)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (bright) android.graphics.Color.WHITE else android.graphics.Color.rgb(12, 20, 32))
        }
        val visual = PlayerVisual(bitmap.asImageBitmap(), thumbnailPalette(bitmap), contentKey = track.uri)
        val artwork = BrowseArtworkScope().apply { keepVisual(track.uri, visual) }
    }

    @Test fun brightArtworkKeepsBothSortCallersAndInformationLight() = inspectCallers(bright = true)
    @Test fun darkArtworkKeepsBothSortCallersAndInformationDark() = inspectCallers(bright = false)

    private fun inspectCallers(bright: Boolean) {
        val fixture = Fixture(bright)
        assertEquals(bright, collectionIsLight(fixture.visual))
        var externalSort by mutableStateOf(false)
        lateinit var host: GlassDialogs
        var pageInk = Color.Unspecified
        var pageDark: Boolean? = null
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalBrowseArtwork provides fixture.artwork, LocalReducedMotion provides true) {
                BrowserScreen(LibraryUiState(fixture.snapshot, loading = false), PlaylistState(loading = false), fixture.route,
                    {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    sorting = externalSort, onSortDismiss = { externalSort = false }, reducedMotion = true,
                    queryDispatcher = Dispatchers.Unconfined, header = {
                        val ink = LocalContentColor.current
                        val dark = LocalFrostedBackdrop.current?.dark
                        SideEffect { pageInk = ink; pageDark = dark }
                    })
            }
        } }
        compose.runOnIdle {
            assertEquals(!bright, pageDark)
            assertEquals(!bright, pageInk.luminance() > .5f)
        }
        // Toolbar sorting already had the right caller; keep it as the control case.
        compose.onNodeWithContentDescription("Sort").performClick()
        assertMountedAppearance(host, !bright)
        assertForeground(compose.onNodeWithText("Sort by"), !bright)
        compose.onNodeWithText("Cancel").performClick()
        // The sibling invocation used to fall back to the light app theme.
        compose.runOnIdle { externalSort = true }
        assertMountedAppearance(host, !bright)
        assertForeground(compose.onNodeWithText("Sort by"), !bright)
        compose.onNodeWithText("Cancel").performClick()
        // Information is requested by the row menu but mounted as a page sibling.
        compose.onNodeWithTag("media-${fixture.track.id}").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Information").performClick()
        assertMountedAppearance(host, !bright)
        assertForeground(compose.onNodeWithText("Duration: 0:00"), !bright)
        compose.onNodeWithText("Go to album").performScrollTo()
        assertForeground(compose.onNodeWithText("Go to album"), !bright)
    }

    @Test fun plainBrowsingStillUsesLightPopupDefaults() {
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides true) {
                BrowserScreen(LibraryUiState(loading = false), PlaylistState(loading = false), BrowseRoute(MainTab.Songs),
                    {}, {}, {}, { _, _, _ -> }, null, null, true, false, sorting = true,
                    reducedMotion = true, queryDispatcher = Dispatchers.Unconfined)
            }
        } }
        assertMountedAppearance(host, false, collectionBackdrop = false)
        assertForeground(compose.onNodeWithText("Sort by"), false)
    }

    @Test fun openSiblingDialogFollowsReplacementArtworkWithoutReplacingItsLifetime() {
        val bright = Fixture(true)
        val dark = Fixture(false)
        var fixture by mutableStateOf(bright)
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalBrowseArtwork provides fixture.artwork, LocalReducedMotion provides true) {
                BrowserScreen(LibraryUiState(fixture.snapshot, loading = false), PlaylistState(loading = false), fixture.route,
                    {}, {}, {}, { _, _, _ -> }, null, null, true, false, sorting = true,
                    reducedMotion = true, queryDispatcher = Dispatchers.Unconfined)
            }
        } }
        assertMountedAppearance(host, false)
        lateinit var original: GlassDialogEntry
        compose.runOnIdle { original = host.entries.single { it.mounted }; fixture = dark }
        compose.waitUntil(5000) { host.entries.any { it.mounted && it.dark } }
        assertMountedAppearance(host, true)
        assertForeground(compose.onNodeWithText("Sort by"), true)
        compose.runOnIdle { assertSame(original, host.entries.single { it.mounted }); fixture = bright }
        compose.waitUntil(5000) { host.entries.any { it.mounted && !it.dark } }
        assertMountedAppearance(host, false)
        assertForeground(compose.onNodeWithText("Sort by"), false)
        compose.runOnIdle { assertSame(original, host.entries.single { it.mounted }) }
    }

    @Test fun brightNestedAndReturningPanelsRetainTheirCollectionInk() = inspectNested(bright = true)
    @Test fun darkNestedAndReturningPanelsRetainTheirCollectionInk() = inspectNested(bright = false)

    private fun inspectNested(bright: Boolean) {
        val fixture = Fixture(bright)
        var parentOpen by mutableStateOf(false)
        var childOpen by mutableStateOf(false)
        lateinit var host: GlassDialogs
        lateinit var anchor: GlassAnchor
        var parentDark: Boolean? = null
        var childDark: Boolean? = null
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalBrowseArtwork provides fixture.artwork) {
                BrowserScreen(LibraryUiState(fixture.snapshot, loading = false), PlaylistState(loading = false), fixture.route,
                    {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = Dispatchers.Unconfined, header = {
                        anchor = rememberGlassAnchor()
                        ActionIcon(R.drawable.ic_ellipsis, "Fixture options", anchor = anchor) { parentOpen = true }
                        if (parentOpen) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
                            GlassAlertDialog({ parentOpen = false }, title = { Text("Parent fixture") }, text = {
                                parentDark = LocalModalBackdrop.current?.dark
                                Column {
                                    Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(32.dp).testTag("parent-ink"))
                                    TextButton({ childOpen = true }) { Text("Open child") }
                                    if (childOpen) GlassAlertDialog({ childOpen = false }, title = { Text("Child fixture") }, text = {
                                        childDark = LocalModalBackdrop.current?.dark
                                        Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(32.dp).testTag("child-ink"))
                                    }, confirmButton = { TextButton({ childOpen = false }) { Text("Close child") } })
                                }
                            }, confirmButton = { TextButton({ parentOpen = false }) { Text("Close parent") } })
                        }
                    })
            }
        } }
        compose.onNodeWithContentDescription("Fixture options").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertMountedAppearance(host, !bright)
        assertForeground(compose.onNodeWithTag("parent-ink", useUnmergedTree = true), !bright)
        compose.onNodeWithText("Open child").performClick()
        compose.runOnIdle {
            assertEquals(!bright, parentDark)
            assertEquals(!bright, childDark)
            assertTrue(host.entries.filter { it.mounted }.all { it.dark == !bright })
        }
        assertForeground(compose.onNodeWithTag("child-ink", useUnmergedTree = true), !bright)
        compose.onNodeWithText("Close child").performClick()
        assertForeground(compose.onNodeWithTag("parent-ink", useUnmergedTree = true), !bright)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Close parent").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            val closing = host.entries.single { it.anchor === anchor }
            assertFalse(closing.mounted)
            assertEquals(!bright, closing.dark)
            assertEquals(!bright, anchor.ink.luminance() > .5f)
        }
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        compose.mainClock.advanceTimeBy(2000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Fixture options").assertIsDisplayed()
        compose.runOnIdle { assertEquals(!bright, anchor.ink.luminance() > .5f) }
    }

    private fun assertMountedAppearance(host: GlassDialogs, dark: Boolean, collectionBackdrop: Boolean = true) {
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = host.entries.single { it.mounted }
            assertEquals("Dialog material must inherit its collection caller", dark, entry.dark)
            if (collectionBackdrop) {
                assertNotNull("Collection dialogs retain their source", entry.anchor.source)
                assertEquals("Detached sheets need the collection backdrop too", dark, entry.anchor.source?.dark)
            } else assertNull("Standalone plain browsing needs no recorded backdrop", entry.anchor.source)
        }
    }

    private var presentationStage = 0

    private fun assertForeground(node: SemanticsNodeInteraction, white: Boolean) {
        val pixels = capturePresentedPopupNodePixels(compose, node, ++presentationStage)
        var ink = 0
        var background = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val value = pixels[x, y].luminance()
            if (if (white) value > .85f else value < .08f) ink++
            if (if (white) value < .5f else value > .5f) background++
        }
        assertTrue("Rendered foreground must contain ${if (white) "white" else "dark"} ink", ink > 4)
        assertTrue("Ink must contrast with the rendered material", background > 4)
    }
}
