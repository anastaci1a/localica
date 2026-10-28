package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Real host and launch controls; no tag write is submitted by these presentation tests. */
class TagEditorThemeInheritanceTest {
    @get:Rule val compose = createComposeRule()
    private val controller get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalicaApplication).tagEditing
    private val track = Track("theme-inheritance:missing", "Presentation song", artist = "Presentation artist",
        albumArtist = "Presentation album artist", album = "Presentation album", fileName = "presentation.mp3", relativePath = "presentation.mp3")
    private val row = SongRow(track.id, track)
    private val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, track.artist, listOf(row), row)
    private data class Declaration(val ink: Color, val colors: ColorScheme, val appearance: GlassAppearance)
    private var declarations = emptyList<Declaration>()

    @Before fun idleController() {
        compose.runOnIdle {
            assertFalse("Presentation fixture must not interrupt an editing job", controller.state.value.busy)
            controller.close(); controller.hideJobs()
        }
    }

    @After fun closeFixture() {
        compose.runOnIdle { if (!controller.state.value.busy) { controller.close(); controller.hideJobs() } }
    }

    private fun appearance(lightInk: Boolean) = GlassAppearance(contrast = if (lightInk) 0f else .43f,
        contrastWeight = 1.4f, whiteTint = .03f, blur = .1f)

    @Composable private fun Scene(lightInk: Boolean, content: @Composable () -> Unit) {
        val source = rememberFrostedBackdrop(lightInk, dark = lightInk)
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) {
                drawRect(if (lightInk) Color(0xFF181818) else Color(0xFFEAEAEA))
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                LocalReducedMotion provides true, content = content)
        }
    }

    /** Inspect declaration values without installing Activity focus/view locals into a Dialog. */
    @Composable private fun ObserveDeclarations() {
        val snapshots = mutableListOf<Declaration>()
        checkNotNull(LocalGlassDialogs.current).entries.filter { it.mounted }.forEach { entry ->
            CompositionLocalProvider(entry.locals) {
                snapshots += Declaration(LocalContentColor.current, MaterialTheme.colorScheme.copy(), LocalGlassAppearance.current)
            }
        }
        SideEffect { declarations = snapshots }
    }

    private fun textColor(node: SemanticsNodeInteraction, text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val color = layouts.first { it.layoutInput.text.text == text }.layoutInput.style.color
        assertTrue("$text has a resolved text color", color.isSpecified)
        return color
    }

    private fun assertPopup(title: String, lightInk: Boolean, expectedAppearance: GlassAppearance = appearance(lightInk)) {
        val node = compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals("Actual popup title keeps launch ink", lightInk, textColor(node, title).luminance() > .5f)
        compose.runOnIdle {
            val current = declarations.last()
            assertEquals(lightInk, current.ink.luminance() > .5f)
            assertEquals(lightInk, current.colors.onSurface.luminance() > .5f)
            assertEquals(lightInk, current.colors.onSurfaceVariant.luminance() > .5f)
            assertEquals("Explicit appearance, including contrast off, survives the host boundary", expectedAppearance, current.appearance)
        }
    }

    private fun assertEditorForeground(lightInk: Boolean) {
        assertPopup("Edit 1 song", lightInk)
        val field = compose.onNode(hasSetTextAction() and hasText(track.title)).performScrollTo().assertIsDisplayed()
        assertEquals("Editable value agrees with the panel", lightInk, textColor(field, track.title).luminance() > .5f)
        val review = compose.onNodeWithText("Review changes").assertIsNotEnabled()
        val disabled = textColor(review, "Review changes")
        assertEquals("Disabled text retains the same ink polarity", lightInk, disabled.copy(alpha = 1f).luminance() > .5f)
        assertTrue("Disabled state still has a distinct opacity", disabled.alpha > 0f && disabled.alpha < 1f)
        val icon = compose.onNodeWithContentDescription("Choose artwork").performScrollTo().assertIsDisplayed()
        val view = (icon.fetchSemanticsNode().root as ViewRootForTest).view
        val pixels = capturePopupPixels(compose, icon) { view }
        val inkPixels = (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
            val c = pixels[x, y]
            if (lightInk) c.red > .85f && c.green > .85f && c.blue > .85f
            else c.red < .2f && c.green < .2f && c.blue < .2f
        } }
        assertTrue("Artwork action has actual foreground pixels in the requested polarity", inkPixels > 12)
    }

    @Test fun actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen() {
        var lightInk by mutableStateOf(true)
        var open by mutableStateOf(false)
        val callbackIdentities = mutableListOf<Any>()
        compose.setContent { LocalicaTheme { GlassPanelTheme(!lightInk) { Scene(lightInk) {
            TagEditorHost(controller) {
                val callback = checkNotNull(LocalTagEdit.current)
                SideEffect { callbackIdentities += callback }
                CompositionLocalProvider(LocalGlassAppearance provides appearance(lightInk)) { GlassPanelTheme(lightInk) {
                    val anchor = rememberGlassAnchor()
                    ActionIcon(R.drawable.ic_edit, "Open launch menu", anchor = anchor) { open = true }
                    CollectionActions(open, anchor, item, PlaylistState(), null, null, emptyList(), {}, { open = false })
                } }
            }
            ObserveDeclarations()
        } } } }
        var previousSession = ""
        for (target in listOf(true, false)) {
            compose.onNodeWithContentDescription("Open launch menu").performClick()
            compose.onNodeWithText("Edit tags").performClick()
            assertEditorForeground(target)
            compose.runOnIdle {
                assertNotEquals(previousSession, controller.state.value.sessionId)
                previousSession = controller.state.value.sessionId
                // Validation fails synchronously before save can start a service or touch a file.
                controller.save(TagPatch(mapOf(EditField.Year to "not a year")))
            }
            val errorText = "Year must be a positive whole number or blank."
            val error = compose.onNodeWithText(errorText).performScrollTo().assertIsDisplayed()
            assertEquals(declarations.last().colors.error, textColor(error, errorText))
            compose.onNode(hasSetTextAction() and hasText(track.title)).performScrollTo().performTextReplacement("Draft title")
            compose.onNodeWithText("Review changes").performClick()
            assertPopup("Save changes to 1 files?", target)
            compose.onNodeWithText("Cancel").performClick()
            assertPopup("Edit 1 song", target)
            compose.runOnIdle { lightInk = !target }
            assertPopup("Edit 1 song", target)
            compose.onNodeWithText("Close editor").performClick()
        }
        compose.runOnIdle { callbackIdentities.forEach { assertSame(callbackIdentities.first(), it) } }
    }

    @Test fun albumInformationAndFooterEditKeepTheirArtworkPolarity() = informationRoute(MediaKind.Album)
    @Test fun artistInformationAndFooterEditKeepTheirArtworkPolarity() = informationRoute(MediaKind.Artist)
    @Test fun albumArtistInformationAndFooterEditKeepTheirArtworkPolarity() = informationRoute(MediaKind.AlbumArtist)

    private fun informationRoute(kind: MediaKind) {
        val snapshot = LibrarySnapshot(tracks = listOf(track))
        val tab = when (kind) { MediaKind.Album -> MainTab.Albums; MediaKind.Artist -> MainTab.Artists; else -> MainTab.AlbumArtists }
        val group = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(tab)).items.single()
        val route = BrowseRoute(tab, group.ref, group.title)
        var lightInk by mutableStateOf(true)
        var opened: BrowserItem? = null
        var pageInk = Color.Unspecified
        compose.setContent { LocalicaTheme { GlassPanelTheme(!lightInk) { Scene(lightInk) {
            TagEditorHost(controller) {
                key(lightInk) {
                    val color = if (lightInk) Color(0xFF181818) else Color(0xFFEAEAEA)
                    val artwork = remember { BrowseArtworkScope().apply {
                        seed(null, group); keepVisual(track.uri, PlayerVisual(palette = PlayerPalette(color, color, color)))
                    } }
                    CompositionLocalProvider(LocalBrowseArtwork provides artwork, LocalGlassAppearance provides appearance(lightInk)) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, { opened = it }, {},
                            { _, _, _ -> }, null, null, true, false, reducedMotion = true, queryDispatcher = Dispatchers.Unconfined,
                            onAppearance = { ink, _ -> pageInk = ink })
                    }
                }
            }
            ObserveDeclarations()
        } } } }
        fun openInformation() {
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText(track.title))
            compose.onNode(hasText(track.title) and hasAnyAncestor(hasTestTag("browser-list"))).performTouchInput { longClick() }
            compose.onNodeWithText("Information").performClick()
        }
        for (target in listOf(true, false)) {
            compose.runOnIdle { lightInk = target; opened = null }
            compose.waitUntil(5000) { pageInk.isSpecified && (pageInk.luminance() > .5f) == target }
            openInformation()
            assertPopup("Song information", target)
            val fileLabel = compose.onNodeWithText("File name").assertIsDisplayed()
            assertEquals(target, textColor(fileLabel, "File name").luminance() > .5f)
            compose.onNodeWithTag("song-information-Album").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(MediaKind.Album, opened?.ref?.kind) }
            openInformation()
            compose.onNodeWithText("Edit tags").performClick()
            assertEditorForeground(target)
            compose.onNodeWithText("Close editor").performClick()
        }
    }

    @Test fun playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost() {
        var lightInk by mutableStateOf(true)
        val polarity = PlayerBackgroundPolarityState()
        val composedInk = AtomicReference<PlayerInkComposition?>(null)
        val launchOwner = AtomicReference<TagEditorLaunches?>(null)
        fun awaitPlayerInk(target: Boolean): Color {
            var readyInk: Color? = null
            try {
                compose.waitUntil(10_000) {
                    val decision = polarity.decision
                    val observed = composedInk.get()
                    // A palette change does not synchronously publish player ink. Require
                    // the real current decision/frame to reach the actual Surface provider.
                    val ready = decision.confidence == PlayerBackgroundConfidence.Current &&
                        decision.lightInk == target && observed != null && observed.frame != null &&
                        observed.decision === decision && observed.frame === polarity.frame &&
                        observed.contentColor.isSpecified && (observed.contentColor.luminance() > .5f) == target
                    if (ready) readyInk = observed!!.contentColor
                    ready
                }
            } catch (timeout: ComposeTimeoutException) {
                val observed = composedInk.get()
                throw AssertionError("Player launch ink was not current/composed for lightInk=$target within 10000ms: " +
                    "decision=${polarity.decision}; composed=${observed?.decision}; ink=${observed?.contentColor}; " +
                    "sameDecision=${observed?.decision === polarity.decision}; sameFrame=${observed?.frame === polarity.frame}; " +
                    "sample=${polarity.sampleDiagnostic}", timeout)
            }
            return checkNotNull(readyInk)
        }
        fun assertLaunchInk(expected: Color) {
            val accepted = compose.runOnIdle {
                checkNotNull(launchOwner.get()?.presentation(controller.state.value.sessionId))
            }
            assertEquals("Artwork launch captures the actual player/menu ink", expected, accepted.ink)
            assertEquals("Artwork popup title uses the accepted launch ink", accepted.ink,
                textColor(compose.onNodeWithText("Choose artwork"), "Choose artwork"))
        }
        compose.setContent { LocalicaTheme { GlassPanelTheme(!lightInk) { Scene(lightInk) {
            TagEditorHost(controller) {
                val launches = checkNotNull(LocalTagEditorLaunches.current)
                SideEffect { launchOwner.set(launches) }
                CompositionLocalProvider(LocalGlassAppearance provides appearance(lightInk),
                    LocalPlayerInkCompositionObserver provides { composedInk.set(it) }) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val color = if (lightInk) Color(0xFF181818) else Color(0xFFEAEAEA)
                        // Measured control footprints can overlap the cover. Supply real,
                        // readable pixels instead of asking the sampler to invent a placeholder.
                        val artwork = remember(color) {
                            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(color.toArgb())
                            }.asImageBitmap()
                        }
                        PlayerSurface(PlaybackState(title = track.title), true, {},
                            InterfaceSettings(reducedMotion = true, artworkTap = ArtworkAction.Change, artworkHold = ArtworkAction.Menu),
                            remember { Animatable(1f) }, {}, {}, {}, {}, {}, {}, track = track,
                            visual = PlayerVisual(artwork = artwork, palette = PlayerPalette(color, color, color)), backgroundPolarity = polarity) {}
                    }
                }
            }
            ObserveDeclarations()
        } } } }
        for (target in listOf(true, false)) {
            compose.runOnIdle { lightInk = target }
            val tapInk = awaitPlayerInk(target)
            compose.onNodeWithTag("artwork-gesture").performClick()
            assertPopup("Choose artwork", target)
            assertLaunchInk(tapInk)
            compose.runOnIdle { assertTrue(controller.state.value.artworkOnly) }
            compose.onNodeWithText("Cancel").performClick()
            val menuInk = awaitPlayerInk(target)
            compose.onNodeWithTag("artwork-gesture").performTouchInput { longClick() }
            // Glass action surfaces preserve the page polarity with canonical control ink;
            // the accepted editor session below must still capture the exact player/menu ink.
            val menuActionInk = if (menuInk.luminance() > .5f) Color.White else Color(0xFF202124)
            assertEquals("Artwork glass-menu action uses canonical ink for the actual player polarity", menuActionInk,
                textColor(compose.onNodeWithText("Change artwork"), "Change artwork"))
            compose.onNodeWithText("Change artwork").performClick()
            assertPopup("Choose artwork", target)
            assertLaunchInk(menuInk)
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    @Test fun customLegacyProvidersAndExplicitInformationEditRemainAuthoritative() {
        var tagCalls = 0; var artCalls = 0; var infoCalls = 0
        var info by mutableStateOf(false)
        val sessionBefore = controller.state.value.sessionId
        compose.setContent { LocalicaTheme { TagEditorHost(controller) {
            CompositionLocalProvider(LocalTagEdit provides { tracks, done -> assertEquals(listOf(track), tracks); tagCalls++; done(true) },
                LocalArtworkEdit provides { selected -> assertEquals(track, selected); artCalls++ }) {
                GlassPanelTheme(true) {
                    val edit = presentedTagEdit(); val artwork = presentedArtworkEdit()
                    Column {
                        ActionTextButton(onClick = { edit?.invoke(listOf(track)) {} }) { Text("Legacy tags") }
                        ActionTextButton(onClick = { artwork?.invoke(track) }) { Text("Legacy artwork") }
                        ActionTextButton(onClick = { info = true }) { Text("Legacy information") }
                    }
                    if (info) SongInformation(track, { info = false }, { infoCalls++ }) {}
                }
            }
        } } }
        compose.onNodeWithText("Legacy tags").performClick()
        compose.onNodeWithText("Legacy artwork").performClick()
        compose.onNodeWithText("Legacy information").performClick()
        compose.onNodeWithText("Edit tags").performClick()
        compose.runOnIdle {
            assertEquals(1, tagCalls); assertEquals(1, artCalls); assertEquals(1, infoCalls)
            assertEquals(sessionBefore, controller.state.value.sessionId)
        }
    }

    @Test fun unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching() {
        var lightInk by mutableStateOf(true)
        compose.setContent { LocalicaTheme { GlassPanelTheme(!lightInk) { Scene(lightInk) {
            TagEditorHost(controller) {
                CompositionLocalProvider(LocalGlassAppearance provides appearance(lightInk)) { GlassPanelTheme(lightInk) {
                    CompositionLocalProvider(LocalContentColor provides Color.Unspecified) {
                        val edit = presentedTagEdit()
                        ActionTextButton(onClick = { edit?.invoke(listOf(track)) {} }) { Text("Launch unspecified ink") }
                    }
                } }
            }
            ObserveDeclarations()
        } } } }
        for (target in listOf(true, false)) {
            compose.runOnIdle { lightInk = target }
            compose.onNodeWithText("Launch unspecified ink").performClick()
            assertPopup("Edit 1 song", target)
            compose.onNodeWithText("Close editor").performClick()
        }
    }

    @Test fun acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens() {
        var lightInk by mutableStateOf(true)
        compose.setContent { LocalicaTheme { GlassPanelTheme(!lightInk) { Scene(lightInk) {
            TagEditorHost(controller) {
                CompositionLocalProvider(LocalGlassAppearance provides appearance(lightInk)) { GlassPanelTheme(lightInk) {
                    val edit = presentedTagEdit()
                    ActionTextButton(onClick = { edit?.invoke(listOf(track)) {} }) { Text("Start presentation session") }
                } }
            }
            ObserveDeclarations()
        } } } }
        compose.onNodeWithText("Start presentation session").performClick()
        assertPopup("Edit 1 song", true)
        val session = controller.state.value.sessionId
        compose.runOnIdle {
            controller.continueInBackground(); controller.showJobs()
            lightInk = false
        }
        assertPopup("Tag editing jobs", true)
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { controller.showJobs() }
        assertPopup("Tag editing jobs", true)
        compose.runOnIdle { assertEquals(session, controller.state.value.sessionId) }
    }

    @Test fun presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure() {
        var state = TagEditorState()
        var serial = 0; var noOp = false; var failLaunch = false
        val edit: (List<Track>, (Boolean) -> Unit) -> Unit = { tracks, _ ->
            when {
                state.busy -> state = state.copy(jobsVisible = true)
                failLaunch -> error("Rejected fixture request")
                !noOp -> state = state.copy(sessionId = "accepted-${++serial}", tracks = tracks, background = false)
                else -> Unit
            }
        }
        val launches = TagEditorLaunches({ state }, edit, { selected ->
            edit(listOf(selected)) {}; if (!state.busy) state = state.copy(artworkOnly = true)
        })
        val light = TagEditorPresentation(Color.White, darkColorScheme(), appearance(true))
        val dark = TagEditorPresentation(Color(0xFF202124), lightColorScheme(), appearance(false))
        launches.openTags(light, listOf(track)) {}
        val first = state.sessionId
        state = state.copy(busy = true, background = true)
        launches.openTags(dark, listOf(track)) {}
        assertTrue(state.jobsVisible); assertEquals(first, state.sessionId)
        assertSame(light, launches.presentation(first))
        state = state.copy(busy = false)
        noOp = true; launches.openTags(dark, listOf(track)) {}
        assertSame(light, launches.presentation(first))
        failLaunch = true
        try { launches.openTags(dark, listOf(track)) {}; fail("Expected fixture failure") }
        catch (_: IllegalStateException) { }
        assertSame(light, launches.presentation(first))
        failLaunch = false; noOp = false
        edit(listOf(track)) {}
        assertNull("An unpresented external open cannot borrow an older session's theme", launches.presentation(state.sessionId))
        launches.openTags(dark, listOf(track)) {}
        assertNotEquals(first, state.sessionId); assertSame(dark, launches.presentation(state.sessionId))
        launches.openArtwork(light, track)
        assertTrue(state.artworkOnly); assertSame(light, launches.presentation(state.sessionId))
    }
}
