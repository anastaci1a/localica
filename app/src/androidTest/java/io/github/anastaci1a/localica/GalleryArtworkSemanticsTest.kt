package io.github.anastaci1a.localica

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryArtworkSemanticsTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun decorativeArtKeepsMetadataActionsAndTransitionNumbers() {
        val retention = BrowseArtworkScope()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        ArtworkCache.configure(context)
        for ((key, color) in listOf("missing" to null, "corrupt" to null,
            "red" to android.graphics.Color.RED, "blue" to android.graphics.Color.BLUE)) {
            retention.hold(key, listOf(key), ArtworkCache.CachedArtwork(color?.let {
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(it) }
            }, 4096))
        }
        val gallery = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        var style by mutableStateOf(gallery)
        var item by mutableStateOf(BrowserItem(MediaRef(MediaKind.Album, "semantics"), "Recording title", "Recording artist", emptyList(), coverUri = "missing"))
        val motion = BrowsePinchMotion()
        var clicks = 0
        var holds = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseArtwork provides retention, LocalReducedMotion provides false) {
                motion.pixelDensity = LocalDensity.current.density
                LookaheadScope {
                    Box(Modifier.width(180.dp).testTag("cell").semantics(mergeDescendants = true) {
                        testTagsAsResourceId = true
                        onClick { clicks++; true }
                        onLongClick { holds++; true }
                    }) {
                        BrowseItemLayout(item, style, BrowseCellGeometry(2), this@LookaheadScope, false,
                            menu = {}, trackNumber = "7", pinch = motion)
                    }
                }
            }
        } }
        fun metadata() {
            compose.onNodeWithTag("cell").assertTextContains("Recording title").assertTextContains("Recording artist")
                .assertHasClickAction().assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        }
        fun census(label: String) {
            metadata()
            val slot = compose.onNodeWithTag("browse-art-${item.key}", true).fetchSemanticsNode()
            assertTrue("Decorative art has one clearing owner: $label", slot.config.isClearingSemantics)
            assertFalse(slot.config.contains(SemanticsProperties.ContentDescription))
            fun debugCount(node: SemanticsNode): Int = 1 + node.children.sumOf(::debugCount)
            fun find(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
                if (node.viewIdResourceName == id) return AccessibilityNodeInfo.obtain(node)
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { find(child, id)?.let { return it } } finally { child.recycle() }
                }
                return null
            }
            val nativeText = mutableSetOf<String>()
            fun count(node: AccessibilityNodeInfo): Int {
                nativeText += node.text?.toString().orEmpty()
                return 1 + (0 until node.childCount).sumOf { index ->
                    val child = requireNotNull(node.getChild(index))
                    try { count(child) } finally { child.recycle() }
                }
            }
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val original = automation.serviceInfo
            val flags = original.flags
            original.flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = original
            try {
                automation.waitForIdle(100, 5000)
                val root = requireNotNull(automation.rootInActiveWindow)
                try {
                    val cell = requireNotNull(find(root, "cell")) { "Public accessibility tree must expose the cell" }
                    try {
                        val art = requireNotNull(find(cell, "browse-art-${item.key}")) { "Public accessibility tree must expose the art owner" }
                        try {
                            val exposedCellNodes = count(cell)
                            val exposedSlotNodes = count(art)
                            Log.i("GalleryArtCensus", "$label debugCellNodes=${debugCount(compose.onNodeWithTag("cell", true).fetchSemanticsNode())} debugSlotNodes=${debugCount(slot)} exposedCellNodes=$exposedCellNodes exposedSlotNodes=$exposedSlotNodes bounds=${slot.boundsInRoot}")
                            assertEquals("Image internals cannot grow the exposed slot: $label", 1, exposedSlotNodes)
                        } finally { art.recycle() }
                        assertTrue(nativeText.any { it.contains("Recording title") })
                        assertTrue(nativeText.any { it.contains("Recording artist") })
                        assertTrue(cell.isClickable && cell.isLongClickable)
                    } finally { cell.recycle() }
                } finally { root.recycle() }
            } finally { original.flags = flags; automation.serviceInfo = original }
            compose.onNodeWithTag("cell").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        }
        for (key in listOf("missing", "corrupt", "red", "blue")) {
            compose.runOnIdle { item = item.copy(coverUri = key) }
            compose.waitForIdle()
            census(key)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { item = item.copy(coverUri = "red") }
            compose.mainClock.advanceTimeBy(80)
            census("resolution blend")
            compose.runOnIdle { style = BrowseStyle(listSize = ListSize.Tracklist) }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("cell").assertTextContains("7")
            compose.runOnIdle {
                motion.viewportWidth = 600f
                motion.sourceColumns = 1
                motion.bounds = mapOf(item.key to Rect(0f, 0f, 180f, 200f))
                motion.source = style
                style = gallery
            }
            for (progress in listOf(0f, .3f, .2f, .4f)) {
                compose.runOnIdle { runBlocking { motion.progress.snapTo(progress) } }
                compose.mainClock.advanceTimeByFrame()
                metadata()
                compose.onNodeWithTag("cell").assertTextContains("7")
            }
            compose.runOnIdle { runBlocking { motion.progress.snapTo(1f) }; motion.source = null }
            compose.mainClock.advanceTimeBy(400)
            census("completed gallery")
            compose.onNodeWithText("7", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { style = BrowseStyle() }
            compose.mainClock.advanceTimeBy(400)
            metadata()
            assertFalse(compose.onNodeWithTag("browse-art-${item.key}", true).fetchSemanticsNode().config.isClearingSemantics)
            compose.onNodeWithContentDescription(context.getString(R.string.album_artwork), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("cell").performClick().performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, holds) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
