package io.github.anastaci1a.localica

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import kotlinx.coroutines.Dispatchers
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val watchdog = BrowseSchedulerWatchdog.rule()
    private var lastRoot = ""
    private var lastMorph = ""

    @Test fun searchSlotUsesPlacedTravelThroughNativeReadinessAndInterruptedReturn() = slotFrames(false)
    @Test fun reducedSearchSlotUsesTheSameNativePlacementEndpoints() = slotFrames(true)
    @Test fun queueRowsLandAtTheDepartingSearchMaterialTop() = slotFrames(false, queue = true)

    private data class SlotFrame(val sample: SearchMorphSample, val rowTop: Float)

    private fun slotFrames(reduced: Boolean, queue: Boolean = false) = withDockedSlotKeyboard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = if (queue) BrowseRoute(MainTab.Queues,
            MediaRef(MediaKind.Queue, "slot-queue"), title = "Slot queue", key = "queue-search-slot-frames")
            else BrowseRoute(MainTab.Songs, key = "search-slot-frames")
        val preferences = BrowsePreferences(context)
        val previous = preferences.read(route)
        preferences.write(route, BrowseStyle(quickSearch = true), false)
        val frames = mutableListOf<SlotFrame>()
        val openReadiness = mutableListOf<String>()
        val placedFrames = mutableListOf<SearchMorphSample>()
        val rootFrames = mutableListOf<SearchSlotLayoutSample>()
        var slot = 0f
        val tracks = (0..20).map { Track("slot:$it", "Song ${it.toString().padStart(3, '0')}",
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queueState = if (queue) {
            val value = io.github.anastaci1a.localica.queue.ListeningQueue(id = "slot-queue", name = "Slot queue",
                entries = tracks.mapIndexed { index, track -> io.github.anastaci1a.localica.queue.QueueEntry("slot-entry:$index", track) },
                currentEntryId = "slot-entry:0")
            io.github.anastaci1a.localica.queue.QueueUiState(
                io.github.anastaci1a.localica.queue.QueueLibrary(listOf(value), value.id), loading = false, browsedId = value.id)
        } else io.github.anastaci1a.localica.queue.QueueUiState()
        try {
            compose.setContent { LocalicaTheme {
                val density = LocalDensity.current
                val slotHeight = listPillSlotHeight()
                val snapshot = remember { LibrarySnapshot(folders = listOf(FolderSelection("slot-root", "Music")), tracks = tracks) }
                SideEffect { slot = with(density) { slotHeight.toPx() } }
                CompositionLocalProvider(LocalReducedMotion provides reduced,
                    LocalSearchSlotLayoutObserver provides { rootFrames += it },
                    LocalSearchMorphObserver provides { sample ->
                        placedFrames += sample
                    }) {
                    Box(Modifier.fillMaxSize()) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                            { _, _, _ -> }, null, null, true, false, reducedMotion = reduced,
                            queueState = queueState, playingEntryId = if (queue) "slot-entry:0" else null,
                            queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 000").fetchSemanticsNodes().isNotEmpty() }
            fun rowNode() = compose.onNodeWithTag("media-${if (queue) "slot-entry:0" else tracks.first().id}", useUnmergedTree = true)
            val baseline = rowNode().fetchSemanticsNode().boundsInRoot.top
            val originalRoot = (rowNode().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            val originalRootY = compose.runOnUiThread {
                IntArray(2).also(originalRoot::getLocationOnScreen)[1].toFloat()
            }
            compose.mainClock.autoAdvance = false
            fun advanceObservedFrame(count: Int = 1) {
                repeat(count) { compose.mainClock.advanceTimeByFrame() }
                // Keep cross-window observations for lifecycle witnesses. Geometry
                // equality uses the independently stamped root layout below.
                compose.waitForIdle()
                val coordinates = rowNode().fetchSemanticsNode().layoutInfo.coordinates
                compose.runOnUiThread {
                    assertTrue("Each frame measures the current mounted occurrence", coordinates.isAttached)
                    placedFrames.lastOrNull()?.let { frames += SlotFrame(it, coordinates.positionInRoot().y) }
                }
            }
            fun pumpUntil(endpoint: Boolean = false, predicate: () -> Boolean) {
                val previousAutoAdvance = compose.mainClock.autoAdvance
                // As at the final return below, this endpoint has no intervening
                // gesture. Let the normal clock settle its springs rather than
                // throttling virtual time through repeated native/semantics yields.
                // Every real placement callback, predicate and wall deadline remains.
                if (endpoint) compose.mainClock.autoAdvance = true
                try {
                    compose.waitUntil(5000) {
                        advanceObservedFrame(if (endpoint) 0 else 1)
                        compose.runOnUiThread(predicate)
                    }
                } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
            }
            fun last() = frames.lastOrNull()?.sample
            fun open() {
                // The returning face hides its trigger from merged accessibility
                // semantics while the underlying real pointer target remains owned.
                compose.onNodeWithTag("list-search-pill", useUnmergedTree = true).performTouchInput { click(center) }
                val wallStart = android.os.SystemClock.uptimeMillis()
                val virtualStart = compose.mainClock.currentTime
                var polls = 0
                var focusedEditors = 0
                try {
                    compose.waitUntil(5000) {
                        // Native traversal/semantics can cost hundreds of wall milliseconds.
                        // Pump a bounded 64ms slice, retaining every actual placement callback,
                        // rather than starving the spring with one virtual frame per poll.
                        advanceObservedFrame(count = 4)
                        polls++
                        focusedEditors = compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size
                        val ready = focusedEditors == 1
                        if (compose.mainClock.currentTime - virtualStart > 5000L)
                            throw AssertionError("Search focus exceeded its 5000ms virtual budget")
                        ready
                    }
                } finally {
                    val receipt = "open polls=$polls wallMillis=${android.os.SystemClock.uptimeMillis() - wallStart} " +
                        "virtualMillis=${compose.mainClock.currentTime - virtualStart} focusedEditors=$focusedEditors " +
                        "last=${compose.runOnUiThread { placedFrames.lastOrNull() }}"
                    openReadiness += receipt
                    android.util.Log.i("ListSearchReadiness", receipt)
                }
            }
            fun beginReturn() {
                // Empty search closes on the IME-owned Back itself. A second Back
                // would target the underlying Activity after input ownership retires.
                compose.awaitSearchImeHandoff(requireShownBeforeAction = true) { pressSystemBack() }
            }
            open()
            fun openingWitness() = frames.firstOrNull { frame -> frame.sample.let {
                it.opening && it.windowFocused && it.ime > 0 && it.nativeIme > 0 &&
                    if (reduced) it.slotFraction == 1f && it.progress == 1f &&
                        kotlin.math.abs(it.bounds.bottom - it.destination.bottom) <= 1.1f
                    else it.placementBottom != null && it.slotFraction in .1f.. .8f
            } }?.sample
            // Focus acknowledgement may follow a valid recorded travel frame. It
            // must not require that an already-observed interval remains current.
            pumpUntil { openingWitness() != null }
            val owned = compose.runOnUiThread { requireNotNull(openingWitness()) }
            assertTrue("Placement must use this popup's actual focused native IME: $owned", owned.windowFocused && owned.ime > 0 && owned.nativeIme > 0)
            beginReturn()
            if (!reduced) {
                pumpUntil { last()?.let { !it.opening && it.slotFraction > .01f && it.slotFraction < owned.slotFraction } == true }
                // A fresh contact during the existing return must retain the original vacancy.
                open()
            } else {
                compose.waitUntil(5000) {
                    advanceObservedFrame()
                    compose.onAllNodesWithTag("list-search-popup", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                }
                open()
            }
            pumpUntil(endpoint = true) { last()?.let { it.opening && it.progress >= .999f && it.slotFraction == 1f } == true }
            beginReturn()
            // No gesture remains to interrupt this final endpoint. Let the normal
            // test clock settle instead of making native traversal cost throttle
            // virtual spring time. The wall deadline and real disposal remain strict.
            compose.mainClock.autoAdvance = true
            try {
                compose.waitUntil(5000) {
                    compose.waitForIdle()
                    // Merged semantics retire on dismissal; the unmerged body
                    // remains mounted until the real return owner reaches its end.
                    compose.onAllNodesWithTag("list-search-popup", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                }
            } finally { compose.mainClock.autoAdvance = false }
            assertEquals("Final restoration keeps the exact original row position", baseline, rowNode().fetchSemanticsNode().boundsInRoot.top, 1.1f)
            val observed = compose.runOnUiThread { frames.toList() }
            val placements = compose.runOnUiThread { placedFrames.toList() }
            assertTrue("Observe a shared opening rectangle before handoff", placements.any { it.opening && it.openingOwnedBounds != null && it.flight?.opening == true })
            val rootLayouts = compose.runOnUiThread { rootFrames.toList() }
            assertTrue("Observe underlying window layouts", rootLayouts.size > 2)
            assertTrue("Root layout observes the fully vacated slot", rootLayouts.any { it.fraction == 1f })
            // The popup's first measured source is the visible material, not its
            // taller touch target. Compare in the original list window's coordinates.
            val initialHome = requireNotNull(placements.first { it.opening && it.flight?.opening == true }.flight).from
            val originalMaterialTop = initialHome.top - originalRootY
            val vacancy = baseline - originalMaterialTop
            assertTrue("Only the real slot is vacated", vacancy in 0f..slot)
            for (sample in rootLayouts)
                assertEquals("Row uses the placement owner's fraction measured in its own traversal: $sample",
                    baseline - vacancy * sample.fraction, sample.rowTop, 1.1f)
            var heldFlight: SearchMorphFlight? = null
            var heldFraction = 0f
            // Keep every callback for owner/trajectory assertions, including those
            // between root-window traversals that cannot pair a new row rectangle.
            for (sample in placements) {
                val flight = sample.flight
                val bottom = sample.placementBottom
                val travel = sample.slotTravel
                // A native-window renewal or interrupted-return preview can start
                // another opening flight before the visible flag changes.
                if (sample.opening && flight?.opening == true && flight != heldFlight) {
                    heldFraction = sample.slotFraction
                    heldFlight = flight
                }
                if (sample.opening && flight?.opening == true && bottom == null)
                    assertEquals("Vacancy holds only before the opening rectangle starts", heldFraction, sample.slotFraction, 0f)
                if (sample.opening && bottom != null && travel != null)
                    assertEquals("Use actual bottom travel, not another timer", travel.fraction(bottom), sample.slotFraction, .0001f)
                if (!sample.opening && flight != null)
                    assertEquals("Reverse the actual captured return flight", sample.slotReturnFrom * (1f - flight.fraction(sample.progress)), sample.slotFraction, .0001f)
                if (reduced) assertTrue("Reduced motion has only placement endpoints", sample.slotFraction == 0f || sample.slotFraction == 1f)
                if (!sample.opening) heldFlight = null
            }
            if (!reduced) {
                assertTrue(observed.any { it.sample.opening && it.sample.slotFraction in .1f.. .9f })
                assertTrue(observed.any { !it.sample.opening && it.sample.slotFraction in .01f.. .99f })
            }
        } finally {
            val observed = compose.runOnUiThread { frames.toList() }
            val additional = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            val directory = java.io.File(additional ?: requireNotNull(context.getExternalFilesDir(null)), "search-slot-frames")
            check(directory.isDirectory || directory.mkdirs())
            val output = java.io.File(directory, "${if (reduced) "reduced" else "normal"}-${android.os.SystemClock.elapsedRealtimeNanos()}.txt")
            output.writeText("PLACED CALLBACKS\n" + compose.runOnUiThread { placedFrames.toList() }.joinToString("\n") +
                "\nROOT LAYOUT OBSERVATIONS\n" + compose.runOnUiThread { rootFrames.toList() }.joinToString("\n") +
                "\nCROSS-WINDOW CALLBACK OBSERVATIONS\n" + observed.joinToString("\n") { "rowTop=${it.rowTop} ${it.sample}" } +
                "\nOPEN READINESS\n" + openReadiness.joinToString("\n"))
            android.util.Log.i("ListSearchSlotFrames", "reduced=$reduced frames=${observed.size} trace=${output.name}")
            observed.takeLast(24).forEach { android.util.Log.i("ListSearchSlotFrames", "rowTop=${it.rowTop} ${it.sample}") }
            compose.mainClock.autoAdvance = true
            preferences.write(route, previous, false)
        }
    }
    private fun withDockedSlotKeyboard(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val key = "show_ime_with_hard_keyboard"
        val original = shell("settings get secure $key")
        check(original == "null" || original.matches(Regex("-?[0-9]+")))
        val scales = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
            .associateWith { shell("settings get global $it") }
        check(scales.values.all { it == "null" || it.toFloatOrNull() != null })
        val setup = mutableListOf<String>()
        try {
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            shell("settings put secure $key 1")
            assertEquals("1", shell("settings get secure $key"))
            scales.keys.forEach { shell("settings put global $it 1"); assertEquals(1f, shell("settings get global $it").toFloat(), 0f) }
            prepareNativeDockedKeyboard(compose, setup, ::shell)
            block()
        } finally {
            setup.forEach { android.util.Log.i("ListSearchSlotSetup", it) }
            if (original == "null") shell("settings delete secure $key") else shell("settings put secure $key $original")
            assertEquals("Restore native keyboard policy", original, shell("settings get secure $key"))
            scales.forEach { (name, value) ->
                if (value == "null") shell("settings delete global $name") else shell("settings put global $name $value")
                assertEquals("Restore native animation scale $name", value, shell("settings get global $name"))
            }
        }
    }
    private fun trace(message: String) {
        android.util.Log.i("ListSearchReopen", "${android.os.SystemClock.uptimeMillis()} $message")
    }

    private fun awaitSearchFocus() {
        // Window focus arrives outside Compose's animation clock. Assert the editor's
        // state directly: a visible IME isn't guaranteed to appear in the accessibility
        // window list. GlassPressureTest separately exercises IME-owned Back via insets.
        var lastCount = -1
        try {
            compose.waitUntil(5000) {
                val count = compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size
                if (count != lastCount) { trace("focusedEditors=$count root=$lastRoot"); lastCount = count }
                count == 1
            }
        } catch (failure: Throwable) {
            runCatching {
                trace("focusTimeout editors=${compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size} popups=${compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().size} root=$lastRoot")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun dismissSearch() {
        val editing = compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        compose.awaitSearchImeHandoff { if (editing) pressSystemBack() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("list-search-popup"))
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=before popup Back")
        pressSystemBack()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=popup removed")
    }

    @Test fun visibleSearchVacancyRestoresWithoutCumulativeListMovement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums)))
        val tracks = (0..30).map { n -> Track("vacancy:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            BrowseSchedulerWatchdog.phase("install list search fixture")
            compose.setContent { LocalicaTheme { CompositionLocalProvider(
                LocalTextEditingObservation provides { trace("text $it") },
                LocalSearchEditTrace provides { trace("editor $it") },
                LocalSearchMorphObserver provides { sample ->
                    val value = "opening=${sample.opening} progressStep=${(sample.progress * 10).toInt()} window=${sample.windowFocused} ime=${sample.ime} nativeIme=${sample.nativeIme} source=${sample.imeSource} target=${sample.imeTarget}"
                    if (lastMorph != value) { lastMorph = value; trace("morph $value") }
                }
            ) {
                val root = LocalView.current
                SideEffect {
                    val value = "activityView=${System.identityHashCode(root)} window=${System.identityHashCode(root.windowToken)} focus=${root.hasWindowFocus()} attached=${root.isAttachedToWindow} shown=${root.isShown}"
                    if (lastRoot != value) { lastRoot = value; trace(value) }
                }
                LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } } }
            BrowseSchedulerWatchdog.phase("await initial album")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            repeat(3) { pass ->
                if (pass == 1) compose.onNodeWithTag("browser-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 20f) }
                val before = compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot
                BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
                awaitSearchFocus()
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                if (pass == 0) {
                    pressSystemBack()
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
                    BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
                    awaitSearchFocus()
                }
                compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
                BrowseSchedulerWatchdog.phase("dismiss list search")
            dismissSearch()
                compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
                assertEquals("Restore both full and partially visible pill space", before,
                    compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }

    @Test fun popupKeepsScopeAndBothListPositionsWhenOpeningAResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums), reducedMotion = true))
        val tracks = (0..99).map { n ->
            val name = n.toString().padStart(3, '0')
            Track("track:$n", "Song $name", album = "Album $name", fileName = "$n.mp3", relativePath = "$n.mp3")
        }
        var observedMorph: SearchMorphSample? = null
        var observedMorphAt = -1L
        val observationFailures = mutableListOf<Throwable>()
        fun observePosition(label: String, row: androidx.compose.ui.semantics.SemanticsNode,
            captured: androidx.compose.ui.geometry.Rect) {
            runCatching { compose.runOnUiThread {
                val root = row.root
                val view = (root as androidx.compose.ui.platform.ViewRootForTest).view
                // Direct public semantics reads on Main: no additional Espresso
                // synchronization, readiness wait, frame or screenshot.
                val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                val props = androidx.compose.ui.semantics.SemanticsProperties
                val viewport = nodes.singleOrNull { it.config.getOrNull(props.TestTag) == "browser-list" }
                val viewportBounds = viewport?.boundsInRoot
                val relative = viewportBounds?.let { captured.translate(-it.topLeft) }
                val neighbors = nodes.mapNotNull { node ->
                    val tag = node.config.getOrNull(props.TestTag)
                    val bounds = node.boundsInRoot
                    if (tag?.startsWith("media-") == true && viewportBounds?.overlaps(bounds) == true)
                        tag to bounds else null
                }.sortedBy { kotlin.math.abs(it.second.top - captured.top) }.take(5)
                val screen = IntArray(2).also(view::getLocationOnScreen)
                val window = IntArray(2).also(view::getLocationInWindow)
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
                android.util.Log.i("SearchResultReturn", "sample=$label nonAtomicPostSample=true " +
                    "root=${System.identityHashCode(root)} view=${System.identityHashCode(view)} " +
                    "window=${view.windowToken?.let(System::identityHashCode)} screen=${screen.contentToString()} " +
                    "windowOrigin=${window.contentToString()} viewSize=${view.width}x${view.height} " +
                    "rootView=${System.identityHashCode(view.rootView)} rootSize=${view.rootView.width}x${view.rootView.height} " +
                    "attached=${view.isAttachedToWindow} focus=${view.hasWindowFocus()} " +
                    "capturedRow=$captured currentRow=${row.boundsInRoot} viewport=$viewportBounds rowRelative=$relative " +
                    "scroll=${viewport?.config?.getOrNull(props.VerticalScrollAxisRange)?.value?.invoke()} " +
                    "neighbors=$neighbors imeVisible=${insets?.isVisible(ime)} ime=${insets?.getInsets(ime)?.bottom} " +
                    "lastMorphAt=$observedMorphAt now=${android.os.SystemClock.uptimeMillis()} lastMorph=$observedMorph")
            } }.exceptionOrNull()?.let { diagnostic ->
                if (observationFailures.size < 2) observationFailures += diagnostic
            }
        }
        try {
            BrowseSchedulerWatchdog.phase("install list search fixture")
            compose.setContent { LocalicaTheme {
                val inheritedObserver = LocalSearchMorphObserver.current
                val observer = remember(inheritedObserver) { { sample: SearchMorphSample ->
                    inheritedObserver?.invoke(sample)
                    observedMorph = sample
                    observedMorphAt = android.os.SystemClock.uptimeMillis()
                } }
                CompositionLocalProvider(LocalSearchMorphObserver provides observer) { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) }
            } }
            BrowseSchedulerWatchdog.phase("await initial album")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            val pill = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
            assertTrue("First item rests below the floating search pill", compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot.top >= pill.bottom)
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.scrollToUncoveredItem(hasText("Album 060"))
            val before = compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot
            BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
            BrowseSchedulerWatchdog.phase("submit list search")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Album 061"))
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(java.io.File(nativeEvidenceDirectory("list-search-return", context.getExternalFilesDir(null)), "list-search-popup.png"))
            val originalResultNode = compose.onNodeWithText("Album 061").fetchSemanticsNode()
            val resultPosition = originalResultNode.boundsInRoot
            observePosition("before-result-navigation", originalResultNode, resultPosition)
            BrowseSchedulerWatchdog.phase("open search result")
            compose.onNodeWithText("Album 061").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
            BrowseSchedulerWatchdog.phase("return to search results")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertTextContains("Album")
            val returnedResultNode = compose.onNodeWithText("Album 061").fetchSemanticsNode()
            val returnedPosition = returnedResultNode.boundsInRoot
            observePosition("after-result-return", returnedResultNode, returnedPosition)
            assertEquals(resultPosition, returnedPosition)
            BrowseSchedulerWatchdog.phase("dismiss list search")
            dismissSearch()
            assertEquals(before, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
            BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNode(hasSetTextAction()).performTextReplacement("061")
            BrowseSchedulerWatchdog.phase("submit list search")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.waitUntil(5000) { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
            assertEquals(before, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
        } catch (failure: Throwable) {
            observationFailures.forEach(failure::addSuppressed)
            throw failure
        } finally {
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }
}
