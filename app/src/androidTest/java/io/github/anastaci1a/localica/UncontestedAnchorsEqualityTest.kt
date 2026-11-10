package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrElse
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

/** Matched native witnesses of the actual hosts, without candidate-only production adapters. */
class UncontestedAnchorsEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    private data class Profile(val policy: String, val font: Float, val rtl: Boolean) {
        val reduced get() = policy == "Reduced"
        val speed get() = if (policy == "Faster") 4f / 3f else 1f
        val key get() = "$policy-font$font-${if (rtl) "rtl" else "ltr"}"
    }
    private val profiles = listOf("Normal", "Faster", "Reduced").flatMap { policy ->
        listOf(1f, 2f).flatMap { font -> listOf(false, true).map { Profile(policy, font, it) } }
    }
    private var profile by mutableStateOf(profiles.first())
    private lateinit var nativeView: android.view.View
    private var clicks = 0
    private var densityScale by mutableFloatStateOf(1f)
    private val textHeights = mutableMapOf<String, Int>()
    private var requestedHeight = 0
    private var homeGeometry = emptyList<Float>()
    private var measurementGeometry = emptyList<Int>()

    @Composable
    private fun Host(height: Int, content: @Composable () -> Unit) {
        LocalicaTheme {
            nativeView = LocalView.current
            requestedHeight = height
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * densityScale, profile.font),
                LocalLayoutDirection provides if (profile.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalReducedMotion provides profile.reduced, LocalMotionSpeed provides profile.speed) {
                val backdrop = rememberFrostedBackdrop(Unit, dark = false, lightMix = 0f, retainRaster = false)
                CompositionLocalProvider(LocalFrostedBackdrop provides backdrop,
                    LocalBrowseSortSnapshot provides BrowseSortSnapshot.Default) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.width(360.dp).height(height.dp).testTag("k-capture")) {
                            Box(Modifier.matchParentSize().recordFrostedBackdrop(backdrop).background(Color.White))
                            Column(Modifier.fillMaxSize()) {
                                Text("Native fixture", Modifier.testTag("k-text-control"), color = Color.Black, fontSize = 12.sp)
                                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun one(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(1)
        return compose.onNode(matcher, useUnmergedTree = true)
    }

    /** Capture on the instrumentation thread. Main returns immutable observations before any I/O. */
    private fun export(name: String) {
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val host = one(hasTestTag("k-capture"))
        val node = host.fetchSemanticsNode()
        val text = one(hasTestTag("k-text-control")).fetchSemanticsNode()
        val native = compose.runOnIdle {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(nativeView))
                .getInsets(WindowInsetsCompat.Type.systemBars())
            val location = IntArray(2).also(nativeView::getLocationOnScreen)
            val b = node.boundsInRoot
            val screen = Rect(b.left + location[0], b.top + location[1], b.right + location[0], b.bottom + location[1])
            val full = nativeView.rootView
            assertTrue("The entire fixture fits below native status bars", screen.top >= insets.top)
            assertTrue("The entire fixture fits above native navigation", screen.bottom <= full.height - insets.bottom)
            assertEquals(profile.font, node.layoutInfo.density.fontScale, 0f)
            with(node.layoutInfo.density) {
                assertEquals("The complete host width is admitted", 360.dp.roundToPx(), node.size.width)
                assertEquals("The complete host height is admitted", requestedHeight.dp.roundToPx(), node.size.height)
            }
            assertTrue("Positive text-size control", text.size.height > 0 && text.size.width > 0)
            val textKey = "${profile.policy}-${profile.rtl}-$densityScale"
            if (profile.font == 1f) textHeights[textKey] = text.size.height
            else assertTrue("Font growth reaches the actual text", text.size.height > requireNotNull(textHeights[textKey]))
            listOf(insets.left, insets.top, insets.right, insets.bottom, full.width, full.height,
                location[0], location[1], screen.left, screen.top, screen.right, screen.bottom)
        }
        val observed = compose.onAllNodes(SemanticsMatcher("All native semantic owners") { true },
            useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { n ->
            val tag = n.config.getOrElse(SemanticsProperties.TestTag) { "" }
            val words = n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("|") { it.text }
            val description = n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString("|")
            if (tag.isEmpty() && words.isEmpty() && description.isEmpty()) null
            else listOf(tag, words, description, n.layoutInfo.isPlaced, n.positionInRoot.x, n.positionInRoot.y,
                n.size.width, n.size.height, n.layoutInfo.density.density, n.layoutInfo.density.fontScale)
        }.sortedBy { it.joinToString("\u0000") }
        val image = host.captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        assertTrue("The fixture has visible ink under ${profile.policy}", pixels.count { (it and 0x00ffffff) != 0x00ffffff } > 20)
        // Retain a same-production repeat, which must be exactly stable before cross-arm comparison.
        val repeat = host.captureToImage()
        val repeated = IntArray(repeat.width * repeat.height)
        repeat.readPixels(repeated)
        assertArrayEquals("The same-production capture is stable", pixels, repeated)
        val directory = nativeEvidenceDirectory("slice-k-anchors", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val extra = compose.runOnIdle { homeGeometry.toList() to measurementGeometry.toList() }
        val receipt = JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("profile", profile.key).put("native", JSONArray(native))
            .put("backdropDark", false).put("backdropLightMix", 0).put("width", image.width).put("height", image.height)
            .put("geometry", JSONArray(observed.map { JSONArray(it) })).put("clicks", clicks)
            .put("homePadding", JSONArray(extra.first)).put("measurement", JSONArray(extra.second))
        File(directory, "$name.json").writeText(receipt.toString(2))
        File(directory, "$name.argb").outputStream().use { output ->
            DataOutputStream(output).use { data -> pixels.forEach(data::writeInt) }
        }
        File(directory, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Retain the untouched native display", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(directory, "$name-display.png")))
        File(directory, "source.txt").writeText(BuildConfig.SOURCE_SHA + "\n")
    }

    @Test fun homeFirstAndLastGeometryFollowMeasuredReturnClearance() {
        val track = Track("k-song", "Pinned fixture", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val library = LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false)
        compose.setContent { Host(300) {
            CompositionLocalProvider(LocalBrowseObscuredInset provides 24.dp) {
            PinnedHome(library, PlaylistState(loading = false), listOf(MediaRef(MediaKind.Song, track.id)),
                onSettings = {}, onBrowse = { clicks++ }, onOpen = { _, _ -> }, onPin = {}, onMove = { _, _ -> },
                reducedMotion = profile.reduced)
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Pinned fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        for (p in profiles) for (scale in listOf(1f, .8f)) {
            compose.runOnIdle { profile = p; densityScale = scale }
            one(hasTestTag("home-list")).performScrollToIndex(0)
            val title = one(hasText("Your music"))
            title.assertIsDisplayed()
            val firstList = one(hasTestTag("home-list")).fetchSemanticsNode()
            val topGap = title.fetchSemanticsNode().positionInRoot.y - firstList.positionInRoot.y
            val topPadding = with(firstList.layoutInfo.density) { 16.dp.roundToPx() }
            assertEquals("The first item preserves its top padding", topPadding.toFloat(), topGap, 0f)
            homeGeometry = listOf(topGap, topPadding.toFloat())
            export("home-${p.key}-density$scale-first")
            one(hasTestTag("home-list")).performScrollToIndex(1)
            one(hasTestTag("home-list")).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
            compose.mainClock.advanceTimeBy(2000)
            one(hasTestTag("pin-${track.id}")).assertIsDisplayed()
            one(hasContentDescription("Return to top")).assertIsDisplayed()
            val list = one(hasTestTag("home-list")).fetchSemanticsNode()
            val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("The list cannot scroll farther forward", range.maxValue(), range.value(), 0f)
            val last = one(hasTestTag("home-pins")).fetchSemanticsNode()
            val control = one(hasClickAction() and hasAnyDescendant(hasContentDescription("Return to top"))).fetchSemanticsNode()
            val bottomGap = list.positionInRoot.y + list.size.height - last.positionInRoot.y - last.size.height
            val expected = with(list.layoutInfo.density) { (24.dp + control.size.height.toDp() + 16.dp).roundToPx() }
            assertEquals("Last-item clearance includes the actual measured return control", expected.toFloat(), bottomGap, 0f)
            homeGeometry = listOf(bottomGap, expected.toFloat(), control.size.height.toFloat(), range.value(), range.maxValue())
            export("home-${p.key}-density$scale-last")
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun queueHeaderWeightsAndDisclosurePreserveOptionalMinimizeAndTypography() {
        val queue = ListeningQueue(id = "k-queue", name = "Queue fixture", createdAt = 0, entries = emptyList())
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        var minimize by mutableStateOf(true)
        var retainedFace by mutableStateOf<(@Composable () -> Unit)?>(null)
        var retainedSize by mutableStateOf(IntSize.Zero)
        compose.setContent { Host(400) {
            val density = LocalDensity.current
            Column {
                Box(Modifier.fillMaxWidth().weight(1f).testTag("k-live")) {
                    QueueScreen(state, null, reducedMotion = profile.reduced,
                        onMinimize = if (minimize) ({ clicks++ }) else null)
                }
                Box(Modifier.size(with(density) { retainedSize.width.toDp() },
                    with(density) { retainedSize.height.toDp() }).testTag("k-retained")) { retainedFace?.invoke() }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val live = hasAnyAncestor(hasTestTag("k-live"))
        for (p in profiles) for (present in listOf(true, false)) {
            compose.runOnIdle { profile = p; minimize = present }
            compose.mainClock.advanceTimeBy(2000)
            val icon = one(hasContentDescription("Select queue") and live).fetchSemanticsNode()
            // The actual descriptor supplies the retained source face, with no production hook.
            compose.runOnIdle {
                val sources = generateSequence(icon.layoutInfo) { it.parentInfo }.mapNotNull { info ->
                    val anchors = info.getModifierInfo().mapNotNull { it.modifier.glassSurfaceAnchor() }
                    if (anchors.isEmpty()) null else info to anchors.single()
                }.toList()
                assertEquals("Exactly one selector source descriptor", 1, sources.size)
                retainedSize = sources.single().first.coordinates.size
                retainedFace = requireNotNull(sources.single().second.face)
            }
            compose.mainClock.advanceTimeBy(2000)
            val shown = one(hasContentDescription("Select queue") and live)
            val copy = one(hasContentDescription("Select queue") and hasAnyAncestor(hasTestTag("k-retained")))
            shown.assertIsDisplayed(); copy.assertIsDisplayed()
            assertEquals("The real retained and live icons have identical bounds", shown.fetchSemanticsNode().size,
                copy.fetchSemanticsNode().size)
            one(hasContentDescription("Sort queue") and live).assertIsDisplayed()
            one(hasContentDescription("Save queue to playlist") and live).assertIsDisplayed()
            if (present) {
                one(hasContentDescription("Minimize queue") and live).performClick()
            } else compose.onAllNodes(hasContentDescription("Minimize queue") and live, useUnmergedTree = true).assertCountEquals(0)
            export("queue-${p.key}-minimize$present")
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun browseMeasurementAlternativesKeepTheirIntrinsicAndUnplacedEnvelope() {
        var intrinsic by mutableStateOf(false)
        var galleryScope by mutableStateOf(false)
        compose.setContent { Host(450) {
            val route = if (galleryScope) BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, "k-artist"))
                else BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "k-queue"))
            val declaration = browseOptionsDeclaration(route, mutable = true, randomize = true, overridden = true, reverse = true)
            Column {
                Text("Natural measurement envelope", color = Color.Black)
                Layout(content = { BrowseOptionsMeasurement(declaration, 12.dp) }, modifier = Modifier.testTag("k-envelope")) { children, constraints ->
                    val child = children.single()
                    val minimum = child.minIntrinsicWidth(Constraints.Infinity)
                    val maximum = child.maxIntrinsicWidth(Constraints.Infinity)
                    val width = if (intrinsic) maximum else 148.dp.roundToPx()
                    // As in StablePopupBody, observe the natural height before any viewport cap.
                    val natural = child.measure(Constraints.fixedWidth(width))
                    assertTrue("The natural measurement is nonempty", natural.width > 0 && natural.height > 0)
                    assertEquals("The declared width reaches the measurement owner", width, natural.width)
                    measurementGeometry = listOf(natural.width, natural.height, minimum, maximum, width)
                    // Production measurement alternatives are never placed. Neither is this observer's child.
                    layout(constraints.maxWidth, 48.dp.roundToPx()) { }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (p in profiles) for (isIntrinsic in listOf(false, true)) for (artist in listOf(false, true)) {
            compose.runOnIdle { profile = p; intrinsic = isIntrinsic; galleryScope = artist }
            compose.mainClock.advanceTimeBy(2000)
            val envelope = one(hasTestTag("k-envelope")).fetchSemanticsNode()
            assertTrue("The inert observer is measured", envelope.size.width > 0 && envelope.size.height > 0)
            // The production measurement owner measures alternatives without placing them.
            compose.onAllNodesWithText("View mode", useUnmergedTree = true).assertCountEquals(0)
            export("browse-${p.key}-intrinsic$isIntrinsic-artist$artist")
        }
        compose.mainClock.autoAdvance = true
    }
}
