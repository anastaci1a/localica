package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueSelectorDragTest {
    @get:Rule val compose = createComposeRule()
    private var enabled by mutableStateOf(true)
    private var present by mutableStateOf(true)
    private var panelReady by mutableStateOf(true)
    private var commits = 0
    private var selections = 0
    private var renames = 0
    private var deletions = 0
    private val results = mutableListOf<Boolean>()
    private val moves = mutableListOf<Pair<String, String?>>()

    private fun fixture(count: Int = 8, width: Int = 380, height: Int = 420, fontScale: Float = 1f,
        rtl: Boolean = false, scale: Float = 1f, densityScale: Float = 1f, reducedMotion: Boolean = false, fullPopup: Boolean = false,
        test: (QueueController, QueueDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            preferences.write(QueueSettings())
            val queues = (0 until count).map { ListeningQueue(id = "q$it", name = "Queue $it", entries = emptyList()) }
            runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(queues, "q0")) }
            val controller = runBlocking(Dispatchers.Main) {
                QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
            }
            runBlocking { withTimeout(5000) { controller.state.first { !it.loading } } }
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * densityScale, fontScale),
                    LocalGlassPanelReady provides panelReady, LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    if (fullPopup) QueueScreen(state, controller, reducedMotion = reducedMotion)
                    else Box(Modifier.width(width.dp).height(height.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                        if (present) QueueSelectorList(state.library.queues, "q2", state.library.activeId, false,
                            reducedMotion, enabled, Modifier.fillMaxSize(),
                            onSelect = { selections++; controller.switchTo(it) }, onRename = { renames++ }, onDelete = { deletions++ },
                            onOffset = { id, offset -> controller.moveQueue(id, offset) },
                            onMove = { id, before, complete ->
                                commits++
                                moves.add(id to before)
                                controller.moveQueueBefore(id, before) { result -> results.add(result); complete(result) }
                            })
                    }
                }
            } }
            compose.waitForIdle()
            test(controller, db)
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            preferences.write(original)
            db.close()
        }
    }

    private fun list() = compose.onNodeWithTag("queue-selector-list")
    private fun localHandle(id: String): Offset = compose.onNodeWithTag("queue-handle-$id", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.center - list().fetchSemanticsNode().boundsInRoot.topLeft
    private fun localBody(id: String): Offset = compose.onNodeWithTag("queue-select-$id", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.center - list().fetchSemanticsNode().boundsInRoot.topLeft
    private fun begin(id: String = "q1"): Offset {
        val start = localBody(id)
        list().performTouchInput { down(start); advanceEventTime(650); moveTo(start + Offset(0f, 35f), delayMillis = 80) }
        compose.onNodeWithTag("drag-stack").assertExists()
        return start
    }
    private fun awaitLanding() = compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
    private fun order(controller: QueueController) = controller.state.value.library.queues.map { it.id }
    private fun assertNoActions() { assertEquals(0, selections); assertEquals(0, renames); assertEquals(0, deletions) }

    @Test fun wholeRowHoldTracksContinuouslyAndCommitsOnlyOnRelease() = fixture { controller, db ->
        val before = controller.state.value
        val originalWidth = compose.onNodeWithTag("queue-row-q1").fetchSemanticsNode().boundsInRoot.width
        val start = begin()
        val first = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
        assertEquals("Lift preserves row width", originalWidth, first.width, 1.5f)
        list().performTouchInput { moveTo(start + Offset(0f, 48f), delayMillis = 80) }
        val second = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
        assertEquals("Sub-row movement must track the finger", 13f, second.top - first.top, 1.5f)
        assertEquals(0, commits)
        assertEquals(before.library, controller.state.value.library)
        val target = compose.onNodeWithTag("queue-row-q4").fetchSemanticsNode().boundsInRoot
        val targetY = target.bottom - list().fetchSemanticsNode().boundsInRoot.top - 2f
        list().performTouchInput { moveTo(Offset(start.x, targetY)); up() }
        awaitLanding()
        assertEquals(listOf(true), results); assertEquals(1, commits)
        assertNotEquals(before.library.queues.map { it.id }, order(controller))
        assertEquals(before.library.queues.associateBy { it.id }, controller.state.value.library.queues.associateBy { it.id })
        assertEquals(before.library.activeId, controller.state.value.library.activeId)
        assertEquals(before.browsedId, controller.state.value.browsedId)
        assertEquals(controller.state.value.library, runBlocking { db.queues().read() })
        assertNoActions()
    }

    @Test fun ordinaryBodyTapSelectsWithoutReordering() = fixture { _, _ ->
        val start = localBody("q1")
        list().performTouchInput { click(start) }
        assertEquals(1, selections); assertEquals(0, commits)
        assertEquals(0, renames); assertEquals(0, deletions)
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
    }

    @Test fun stationaryBodyHoldDoesNotActivateQueueAndNextTapStillWorks() = fixture { _, _ ->
        val start = localBody("q1")
        list().performTouchInput { down(start); advanceEventTime(650); up() }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        assertEquals(0, commits); assertNoActions()
        list().performTouchInput { click(start) }
        assertEquals(1, selections)
    }

    @Test fun bodyHoldInterruptedBeforeAcquisitionDoesNotReorder() = fixture { _, _ ->
        val start = localBody("q1")
        list().performTouchInput { down(start); advanceEventTime(100); cancel() }
        list().performTouchInput {
            down(start); down(1, start + Offset(40f, 0f)); advanceEventTime(650)
            moveBy(Offset(0f, 80f)); up(1); up(0)
        }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        assertEquals(0, commits); assertNoActions()
    }

    @Test fun secondFingerAfterStationaryHoldCancelsBeforeLiftAndNextHoldWorks() = fixture { controller, _ ->
        val before = order(controller)
        val start = localBody("q1")
        list().performTouchInput { down(start); advanceEventTime(650); moveTo(start) }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        list().performTouchInput {
            down(1, start + Offset(40f, 0f))
            moveTo(0, start + Offset(0f, 80f)); up(1)
            moveTo(0, start + Offset(0f, 120f)); up(0)
        }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
        begin()
        list().performTouchInput { cancel() }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun scaledRtlActionHoldsStayExcludedWhenDraggedAcrossRowBody() = fixture(
        count = 40, rtl = true, scale = .8f) { controller, _ ->
        val before = order(controller)
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val body = compose.onNodeWithTag("queue-select-q1", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        val range = compose.onNodeWithTag("queue-selector-grid").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val initialScroll = range.value()
        for (label in listOf("Rename Queue 1", "Delete Queue 1")) {
            val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot.center - origin
            assertTrue("RTL actions must be left of the row body", action.x < body.x)
            root.performTouchInput { click(action) }
            root.performTouchInput {
                down(action); advanceEventTime(650); moveTo(body)
                moveTo(body + Offset(0f, 80f)); up()
            }
            compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        }
        assertEquals(1, renames); assertEquals(1, deletions)
        assertEquals(0, selections); assertEquals(0, commits)
        assertEquals(before, order(controller)); assertEquals(initialScroll, range.value(), .001f)
    }

    @Test fun trailingActionsKeepTapAndHoldDragOwnership() = fixture { _, _ ->
        val rename = compose.onNodeWithContentDescription("Rename Queue 1")
        rename.performTouchInput { click() }
        assertEquals(1, renames)
        val delete = compose.onNodeWithContentDescription("Delete Queue 1")
        delete.performTouchInput { click() }
        assertEquals(1, deletions)
        for (action in listOf(rename, delete)) {
            action.performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, 80f)); up() }
            compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        }
        assertEquals(1, renames); assertEquals(1, deletions)
        assertEquals(0, selections); assertEquals(0, commits)
    }

    @Test fun unchangedDropAcknowledgesWithoutWriting() = fixture { controller, db ->
        val before = order(controller)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_noop_write BEFORE UPDATE ON queues BEGIN SELECT RAISE(ABORT, 'Unexpected write'); END")
        begin()
        list().performTouchInput { up() }
        awaitLanding()
        assertEquals(listOf(true), results); assertEquals(1, commits)
        assertEquals(before, order(controller)); assertNull(controller.state.value.problem); assertNoActions()
    }

    @Test fun cancelTargetAndPointerCancellationDoNotSave() = fixture { controller, _ ->
        val before = order(controller)
        begin()
        val cancel = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.center - list().fetchSemanticsNode().boundsInRoot.topLeft
        list().performTouchInput { moveTo(cancel); up() }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits)
        begin()
        list().performTouchInput { cancel() }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun disablingOrUnmountingDuringDragNeverCommits() = fixture { controller, _ ->
        val before = order(controller)
        begin()
        compose.runOnIdle { enabled = false }
        list().performTouchInput { up() }
        awaitLanding()
        compose.runOnIdle { enabled = true }
        begin()
        compose.runOnIdle { present = false }
        compose.onRoot().performTouchInput { cancel() }
        compose.runOnIdle { present = true }
        compose.waitForIdle()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun secondPointerCancelsInsteadOfHandingOffToScroll() = fixture { controller, _ ->
        val before = order(controller)
        val start = begin()
        list().performTouchInput { down(1, start + Offset(80f, 0f)); moveBy(Offset(0f, 80f)); up(1); up(0) }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun failedPersistenceLandsBackAndRetryWorks() = fixture { controller, db ->
        val before = order(controller)
        fun movePastMeasuredRow() {
            val start = begin()
            val target = compose.onNodeWithTag("queue-row-q4").fetchSemanticsNode().boundsInRoot
            val targetY = target.bottom - list().fetchSemanticsNode().boundsInRoot.top - 2f
            list().performTouchInput { moveTo(Offset(start.x, targetY)); up() }
            awaitLanding()
            val (id, beforeId) = moves.last()
            assertEquals("q1", id)
            val requested = before.filterNot { it == id }.toMutableList().apply {
                add(beforeId?.let { indexOf(it) } ?: size, id)
            }
            assertNotEquals("Rejection must exercise a real reorder, not a successful no-op", before, requested)
        }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_selector_move BEFORE UPDATE ON queues BEGIN SELECT RAISE(ABORT, 'Injected failure'); END")
        movePastMeasuredRow()
        assertEquals(listOf(false), results); assertEquals(before, order(controller))
        assertNotNull(controller.state.value.problem)
        assertEquals(before, runBlocking { db.queues().read() }.queues.map { it.id })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_selector_move")
        movePastMeasuredRow()
        assertEquals(listOf(false, true), results); assertNotEquals(before, order(controller)); assertNoActions()
        assertEquals(order(controller), runBlocking { db.queues().read() }.queues.map { it.id })
    }

    @Test fun concurrentRemovalCancelsStaleDragWithoutRestoringRemovedQueue() = fixture { controller, _ ->
        begin()
        controller.removeQueues(setOf("q3"))
        compose.waitUntil(5000) { "q3" !in order(controller) }
        list().performTouchInput { up() }
        awaitLanding()
        assertFalse("q3" in order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun renameDuringDragRetainsCurrentMetadataAfterDrop() = fixture { controller, _ ->
        val start = begin()
        controller.rename("q1", "Updated queue")
        compose.waitUntil(5000) { controller.state.value.library.queues.first { it.id == "q1" }.name == "Updated queue" }
        compose.onNodeWithText("2. Updated queue").assertExists()
        list().performTouchInput { moveTo(start + Offset(0f, 150f)); up() }
        awaitLanding()
        assertEquals(listOf(true), results)
        assertEquals("Updated queue", controller.state.value.library.queues.first { it.id == "q1" }.name)
        assertNoActions()
    }

    @Test fun ordinaryRowSwipeScrollsAndHandleTapDoesNotSelect() = fixture(count = 40) { _, _ ->
        val handle = localHandle("q1")
        list().performTouchInput { click(handle) }
        assertEquals(0, commits); assertNoActions()
        list().performTouchInput { swipe(Offset(center.x, height * .8f), Offset(center.x, height * .2f)) }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        compose.onNodeWithTag("queue-row-q0").assertDoesNotExist()
        assertEquals(0, commits); assertNoActions()
    }

    @Test fun reducedMotionRtlLargeFontShortViewportKeepsActionsAndCancellation() = fixture(
        count = 20, width = 320, height = 240, fontScale = 1.6f, rtl = true, reducedMotion = true) { controller, _ ->
        val before = order(controller)
        val handle = compose.onNodeWithTag("queue-handle-q1", useUnmergedTree = true).fetchSemanticsNode()
        val row = compose.onNodeWithTag("queue-row-q1").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL handle must be on the row's right", handle.boundsInRoot.center.x > row.center.x)
        val actions = handle.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move up", "Move down"), actions.map { it.label })
        begin()
        list().performTouchInput { cancel() }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits)
        compose.runOnIdle { assertTrue(actions.first { it.label == "Move up" }.action()) }
        compose.waitUntil(5000) { order(controller).first() == "q1" }
    }

    @Test fun edgeScrollingKeepsThePointerAndStillCancels() = fixture(count = 60) { controller, _ ->
        val before = order(controller)
        val start = begin()
        list().performTouchInput { moveTo(Offset(start.x, height - 2f)) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue-row-q0").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("drag-stack").assertExists()
        list().performTouchInput { moveTo(Offset(start.x, 2f)) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue-row-q0").fetchSemanticsNodes().isNotEmpty() }
        list().performTouchInput { cancel() }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits); assertNoActions()
    }

    @Test fun tinyViewportStillScrollsDownAndContainsCancelTarget() = fixture(count = 60, height = 48) { controller, _ ->
        val before = order(controller)
        val start = localHandle("q0")
        compose.mainClock.autoAdvance = false
        try {
            list().performTouchInput { down(start); advanceEventTime(650); moveTo(start + Offset(0f, 35f)) }
            compose.mainClock.advanceTimeByFrame()
            val viewport = list().fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
            assertTrue(cancel.top >= viewport.top - 1f && cancel.bottom <= viewport.bottom + 1f)
            val range = compose.onNodeWithTag("queue-selector-grid").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val initialPosition = range.value()
            list().performTouchInput { moveTo(Offset(start.x, height - 2f)) }
            repeat(30) { compose.mainClock.advanceTimeByFrame() }
            assertTrue("Bottom edge must scroll downward even below the default edge height", range.value() > initialPosition)
            list().performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
        awaitLanding()
        assertEquals(before, order(controller)); assertEquals(0, commits)
    }

    @Test fun retainedListReopensAtPlaybackAfterInterruptedDismissal() = fixture(count = 40) { _, _ ->
        list().performTouchInput { swipe(Offset(center.x, height * .8f), Offset(center.x, height * .2f)) }
        compose.onNodeWithTag("queue-row-q0").assertDoesNotExist()
        compose.runOnIdle { enabled = false }
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("Playback queue").assertIsDisplayed()
    }

    @Test(timeout = 15000) fun openingGateRejectsHeldContactEvenWhenPanelBecomesReady() = fixture { _, _ ->
        val phase = java.util.concurrent.atomic.AtomicReference("fixture ready")
        fun mark(value: String) {
            phase.set(value)
            android.util.Log.i("QueueSelectorDragTest", "opening gate: $value")
        }
        val testThread = Thread.currentThread()
        val mainThread = android.os.Looper.getMainLooper().thread
        val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "queue-gate-watchdog").apply { isDaemon = true }
        }
        watchdog.schedule({
            android.util.Log.e("QueueSelectorDragTest", "opening gate watchdog: ${phase.get()}")
            for (thread in listOf(mainThread, testThread).distinct()) {
                android.util.Log.e("QueueSelectorDragTest", "thread=${thread.name} state=${thread.state}")
                thread.stackTrace.take(80).forEach { android.util.Log.e("QueueSelectorDragTest", "  at $it") }
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS)
        var view: android.view.View? = null
        var held = false
        var pointer = Offset.Zero
        var downTime = 0L
        var eventTime = 0L
        fun send(action: Int, point: Offset = pointer) {
            pointer = point
            eventTime = maxOf(android.os.SystemClock.uptimeMillis(), eventTime + 1)
            if (action == android.view.MotionEvent.ACTION_DOWN) { downTime = eventTime; held = true }
            compose.runOnUiThread {
                val target = checkNotNull(view)
                val location = IntArray(2).also(target::getLocationOnScreen)
                val properties = android.view.MotionEvent.PointerProperties().apply {
                    id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                }
                val coordinates = android.view.MotionEvent.PointerCoords().apply {
                    x = point.x + location[0]; y = point.y + location[1]; pressure = 1f; size = 1f
                }
                val event = android.view.MotionEvent.obtain(downTime, eventTime, action, 1,
                    arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                try {
                    val delivered = target.dispatchTouchEvent(event)
                    // Compose consumes cancellation while legitimately returning false.
                    if (action != android.view.MotionEvent.ACTION_CANCEL)
                        assertTrue("The captured root must receive the pointer stream", delivered)
                } finally { event.recycle() }
            }
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) held = false
        }
        try {
            mark("before handle lookup")
            val handle = compose.onNodeWithTag("queue-handle-q1", useUnmergedTree = true).fetchSemanticsNode()
            val start = handle.boundsInRoot.center
            view = (handle.root as androidx.compose.ui.platform.ViewRootForTest).view
            mark("before close gate")
            compose.runOnIdle { panelReady = false }
            mark("after close gate")
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeByFrame()
            mark("closed gate frame applied")
            // Native events keep one pointer stream without querying semantics while held.
            // Such a query waits for Espresso idleness during API36 stretch rendering.
            mark("before down injection")
            send(android.view.MotionEvent.ACTION_DOWN, start)
            mark("after down injection")
            send(android.view.MotionEvent.ACTION_MOVE, start + Offset(0f, 35f))
            mark("after held move; before gate UI call")
            compose.runOnUiThread { mark("inside gate UI call"); panelReady = true; mark("gate state written") }
            mark("after gate UI call; before frame")
            compose.mainClock.advanceTimeByFrame()
            mark("after frame; before release injection")
            send(android.view.MotionEvent.ACTION_MOVE, start + Offset(0f, 140f))
            send(android.view.MotionEvent.ACTION_UP)
            mark("after release injection")
        } finally {
            try {
                mark("before pointer cleanup")
                if (held) send(android.view.MotionEvent.ACTION_CANCEL)
                mark("after pointer cleanup")
            } finally {
                compose.mainClock.autoAdvance = true
                watchdog.shutdownNow()
            }
        }
        mark("before no-acquisition assertions")
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        assertEquals(0, commits); assertNoActions()
        mark("assertions passed")
    }

    @Test fun scaledRtlHandleUsesLocalHitBoundsAndKeepsLiftWidth() = fixture(rtl = true, scale = .8f) { _, _ ->
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val handle = compose.onNodeWithTag("queue-handle-q1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - origin
        val rowWidth = compose.onNodeWithTag("queue-row-q1").fetchSemanticsNode().boundsInRoot.width
        root.performTouchInput { down(handle); advanceEventTime(650); moveTo(handle + Offset(0f, 45f)) }
        val lifted = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
        assertEquals(rowWidth, lifted.width, 1.5f)
        root.performTouchInput { cancel() }
        awaitLanding()
        assertEquals(0, commits); assertNoActions()
    }

    @Test fun wideViewportDoesNotLetReorderActivateRenameDeleteOrBrowse() = fixture(width = 800, height = 600, densityScale = .5f) { _, _ ->
        begin()
        list().performTouchInput { moveTo(Offset(width - 24f, center.y)); cancel() }
        awaitLanding()
        assertEquals(0, commits); assertNoActions()
        compose.onNodeWithContentDescription("Rename Queue 1").performClick()
        compose.onNodeWithContentDescription("Delete Queue 1").performClick()
        assertEquals(1, renames); assertEquals(1, deletions)
    }

    @Test fun constrainedPopupKeepsListAndFooterReachableAtLargeFont() = fixture(
        count = 20, fontScale = 1.6f, fullPopup = true) { controller, _ ->
        val before = order(controller)
        compose.onNodeWithContentDescription("Select queue").performClick()
        compose.awaitExpectedWindowFocus(list())
        val node = list().fetchSemanticsNode()
        val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        val window = (view.parent as androidx.compose.ui.window.DialogWindowProvider).window
        val originalWidth = window.attributes.width
        val originalHeight = window.attributes.height
        val density = view.resources.displayMetrics.density
        val originalListHeight = node.boundsInRoot.height
        try {
            compose.runOnIdle { window.setLayout((320 * density).toInt(), (220 * density).toInt()) }
            compose.waitUntil(5000) { list().fetchSemanticsNode().boundsInRoot.height < originalListHeight }
            val viewport = list().fetchSemanticsNode().boundsInRoot
            assertTrue("Short popup must retain a nonempty scrolling list", viewport.height > 0f)
            compose.onNodeWithText("Queues", substring = false).assertDoesNotExist()
            compose.onNodeWithText("Remove all other queues").assertIsDisplayed()
            begin("q0")
            val cancel = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
            assertTrue(cancel.top >= viewport.top - 1f && cancel.bottom <= viewport.bottom + 1f)
            list().performTouchInput { cancel() }
            awaitLanding()
            assertEquals(before, order(controller))
        } finally { compose.runOnIdle { window.setLayout(originalWidth, originalHeight) } }
    }

    @Test fun popupDismissReopenStartsAtPlaybackAndCancelsHeldReorder() = fixture(count = 30, fullPopup = true) { controller, _ ->
        val before = order(controller)
        compose.onNodeWithContentDescription("Select queue").performClick()
        compose.onNodeWithText("Playback queue").assertIsDisplayed()
        begin()
        compose.awaitExpectedWindowFocus(list())
        val dialog = compose.onNode(isDialog())
        compose.mainClock.autoAdvance = false
        try {
            pressSystemBack()
            dialog.performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("queue-selector-list").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Select queue").performClick()
        compose.onNodeWithText("Playback queue").assertIsDisplayed()
        assertEquals(before, order(controller))
    }
}
