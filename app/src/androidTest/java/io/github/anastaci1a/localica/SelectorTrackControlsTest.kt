package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.TabLensStyle
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production controls; optical raster registration has its own acceptance gate. */
class SelectorTrackControlsTest {
    @get:Rule val compose = createComposeRule()
    private var reviewStage by mutableIntStateOf(0)
    private lateinit var reviewView: android.view.View
    private var reviewName: String? = null

    @Test fun navbarPullMovesTheShellLabelsAndSelectorTowardTheSameWall() = navbar(TabLensStyle.Adjustable)
    @Test fun clearOverlayPullKeepsItsCurrentInkAndShellGeometry() = navbar(TabLensStyle.Overlay)

    private fun navbar(style: TabLensStyle) {
        reviewName = style.name
        var source: FrostedBackdrop? = null
        compose.setContent { LocalicaTheme {
            reviewView = LocalView.current
            val backdrop = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            source = backdrop
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(tabStyle = style,
                overlayForeground = true, blur = 0f, whiteTint = 0f, contrast = 0f)) {
                Box(Modifier.fillMaxSize().testTag("selector-review"), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backdrop)) {
                        val cell = 24.dp.toPx()
                        for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt())
                            drawRect(if ((x + y) % 2 == 0) Color(0xffc8e5ef) else Color(0xffe8d9bc),
                                Offset(x * cell, y * cell), Size(cell, cell))
                    }
                    Box(Modifier.size(280.dp, 64.dp)) {
                        SlidingTabs(listOf(MainTab.Home, MainTab.Library, MainTab.Songs), MainTab.Home,
                            { 0f }, source = backdrop) {}
                    }
                    PresentationMarker(Modifier.align(Alignment.TopStart)) { reviewStage }
                }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.lensSnapshot != null }
        checkPull("tab-track-host", "tab-track-shell", "tab-selector", "tab-option-content-0", false)
    }

    private fun review(stage: String) {
        val name = reviewName ?: return
        if (InstrumentationRegistry.getArguments().getString("selectorReview") != "true") return
        compose.runOnIdle { reviewStage++ }
        compose.mainClock.advanceTimeByFrame()
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("selector-review"), reviewStage) { reviewView }
        val image = android.graphics.Bitmap.createBitmap(IntArray(pixels.width * pixels.height) {
            pixels[it % pixels.width, it / pixels.width].toArgb()
        }, pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "selector-$name-$stage.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    @Test fun horizontalChoicesShareTheSameContactGeometry() {
        choices(horizontal = true)
        checkPull("choice-track-host", "choice-track-shell", "choice-lens", "choice-option-content-0", false)
    }

    @Test fun verticalChoicesStretchHeightAndMoveTheirTopContact() {
        choices(horizontal = false)
        checkPull("choice-track-host", "choice-track-shell", "choice-lens", "choice-option-content-0", true)
    }

    @Test fun rtlChoiceTargetsRemainInsideTheTrackInMirroredOrder() {
        var selected by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassChoices(listOf("First", "Second", "Third"), selected,
                        Modifier.width(280.dp), horizontal = true) { selected = it }
                }
            }
        } }
        val host = compose.onNodeWithTag("choice-track-host").getUnclippedBoundsInRoot()
        val targets = (0..2).map { compose.onNodeWithTag("choice-option-$it").getUnclippedBoundsInRoot() }
        targets.forEach { target ->
            assertTrue("Physical placement must not apply RTL alignment a second time", target.left >= host.left && target.right <= host.right)
        }
        assertTrue(targets[0].left > targets[1].left && targets[1].left > targets[2].left)
        compose.onNodeWithText("First").performTouchInput { swipe(center, Offset(-width * 1.5f, centerY), 350) }
        compose.onNodeWithText("Third").assertIsSelected()
        compose.onNodeWithText("Third").performTouchInput { swipe(center, Offset(width * 2.5f, centerY), 350) }
        compose.onNodeWithText("First").assertIsSelected()
    }

    private fun choices(horizontal: Boolean) {
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassChoices(listOf("First", "Second", "Third"), 0, Modifier.width(280.dp), horizontal = horizontal) {}
            }
        } }
    }

    // Unlike getUnclippedBoundsInRoot (position + untransformed layout size),
    // mapped corners include every ancestor graphics layer without clipping.
    private fun transformedBounds(tag: String): Rect {
        val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        val size = coordinates.size
        val corners = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f),
            Offset(0f, size.height.toFloat()), Offset(size.width.toFloat(), size.height.toFloat()))
            .map { coordinates.localToRoot(it) }
        return Rect(corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun checkPull(hostTag: String, shellTag: String, lensTag: String, contentTag: String, vertical: Boolean) {
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.waitForIdle()
        val host = compose.onNodeWithTag(hostTag, useUnmergedTree = true)
        val restingHost = bounds(hostTag)
        val shell = bounds(shellTag)
        val lens = bounds(lensTag)
        val content = transformedBounds(contentTag)
        review("rest")
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            host.performTouchInput {
                down(if (vertical) Offset(centerX, height / 6f) else Offset(width / 6f, centerY))
                held = true
                moveBy(if (vertical) Offset(0f, -100f) else Offset(-100f, 0f), delayMillis = 100)
            }
            compose.mainClock.advanceTimeBy(800)
            val stretched = bounds(shellTag)
            val pressed = bounds(lensTag)
            val movedContent = transformedBounds(contentTag)
            review("held")
            assertEquals(restingHost, bounds(hostTag))
            val oldGap = if (vertical) lens.top.value - shell.top.value else lens.left.value - shell.left.value
            val newGap = if (vertical) pressed.top.value - stretched.top.value else pressed.left.value - stretched.left.value
            android.util.Log.i("SelectorTrack", "$hostTag shell=$shell lens=$lens stretched=$stretched pressed=$pressed content=$content movedContent=$movedContent")
            val expectedGap = if (hostTag == "choice-track-host") with(compose.density) {
                ChoiceInnerInset.roundToPx().toDp().value
            } else 4f
            assertEquals("The resting lens uses the independently rounded layout inset", expectedGap, oldGap, .1f)
            assertTrue("The contact gap must progressively close: $oldGap -> $newGap", newGap < oldGap - .1f)
            val extension = if (vertical) stretched.height.value - shell.height.value else stretched.width.value - shell.width.value
            assertTrue("Track extension stays inside the shared physical cap", extension <= SelectorMaximumExtension.value + 1f / compose.density.density)
            assertEquals("The held lens preserves its resting cross dimension",
                if (vertical) lens.width.value else lens.height.value,
                if (vertical) pressed.width.value else pressed.height.value, .1f)
            assertTrue("The outer track stretches along travel", if (vertical) stretched.height > shell.height else stretched.width > shell.width)
            assertTrue("Option content follows the moving wall", if (vertical) movedContent.top + movedContent.bottom < content.top + content.bottom else movedContent.left + movedContent.right < content.left + content.right)
            assertTrue("Option content deforms on the travel axis", if (vertical) movedContent.height > content.height else movedContent.width > content.width)
            host.performTouchInput { up(); held = false }
            compose.mainClock.advanceTimeBy(3000)
            assertEquals(restingHost, bounds(hostTag))
            val settled = bounds(shellTag)
            assertEquals(shell.width.value, settled.width.value, .1f)
            assertEquals(shell.height.value, settled.height.value, .1f)
            review("released")
        } finally {
            if (held) host.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }
}
