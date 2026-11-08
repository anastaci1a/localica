package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*

/** The da41387f on-screen bare control, unchanged, has no rings, filtering or retained cache. */
private fun DrawScope.bareDestinationCutout(bodies: List<Pair<RoundRect, Float>>) {
    val origin = Offset(bodies.minOf { it.first.left }, bodies.minOf { it.first.top })
    val geometry = GlassShadowGeometry().apply { update(shadowScreenBodies(bodies, -origin).map { it.first }, 0f, 0f, false) }
    translate(origin.x, origin.y) { clipPath(geometry.body, ClipOp.Difference) { drawRect(Color.Black, -origin, size) } }
}

/** API26 retains the proven software mask operations; newer APIs use the conformant native mask. */
internal fun destinationShadowCutoutReference(rule: ComposeContentTestRule, width: Int, height: Int,
    bodies: List<Pair<RoundRect, Float>>): ImageBitmap = if (Build.VERSION.SDK_INT >= 29) {
    nativeShadowCutoutReference(width, height, bodies)
} else ImageBitmap(width, height).also {
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(it), Size(it.width.toFloat(), it.height.toFloat())) {
        drawRect(Color.White); bareDestinationCutout(bodies)
    }
}

internal fun verifyNativeDestinationCutoutReference(rule: ComposeContentTestRule) {
    var shift by mutableStateOf(0f)
    lateinit var bodies: List<Pair<RoundRect, Float>>
    lateinit var clock: UiClock
    rule.setContent {
        val currentClock = LocalUiClock.current
        SideEffect { clock = currentClock }
        Box(Modifier.size(240.dp).background(Color.White).testTag("diagnostic-cutout").drawBehind {
            bodies = listOf(60f, 110f).map { top -> RoundRect(60.dp.toPx() + .25f + shift,
                top.dp.toPx() + .75f + shift, 160.dp.toPx() + .25f + shift,
                (top + 40).dp.toPx() + .75f + shift, CornerRadius(20.dp.toPx())) to 1f }
            bareDestinationCutout(bodies)
        })
    }
    rule.waitForIdle(); rule.mainClock.autoAdvance = false
    try { ShadowNativeCapture(rule, rule.onNodeWithTag("diagnostic-cutout")) { clock.stampNow.sequence }.use { native ->
        for (delta in listOf(0f, .5f)) {
            rule.runOnIdle { shift = delta }; rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            val actual = native.capture { scene -> ShadowDrawState(clock.stampNow.sequence, 0,
                shadowScreenBodies(bodies, scene.topLeft)) }
            val state = actual.nativeWindows.single().state
            val expected = destinationShadowCutoutReference(rule, actual.image.width, actual.image.height, state.bodies)
            val blank = ImageBitmap(expected.width, expected.height)
            CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(blank), Size(blank.width.toFloat(), blank.height.toFloat())) { drawRect(Color.White) }
            val scene = checkNotNull(actual.sceneBounds)
            val roi = Rect(scene.left, scene.top + 10f, scene.right, scene.bottom)
            check(state.bodies.all { (r, _) -> val edge = Rect(r.left, r.top, r.right, r.bottom).inflate(2f)
                roi.contains(edge.topLeft) && roi.contains(edge.bottomRight) })
            val reference = actual.copy(image = expected, offscreen = true)
            val probe = ShadowFieldProbe(listOf(roi), 1f / 255f)
            val result = probe.compare(actual, reference, actual.copy(image = blank, offscreen = true))
            shadowOracleEvidence("native-cutout-conformance-$delta", actual, reference, result)
            // Same input-derived whole-contour rectangle +2px used by da41387f's preserved comparison.
            // Full-scene output above is unchanged, including status-clock chrome outside this claim.
            val contour = Rect(kotlin.math.floor(state.bodies.minOf { it.first.left } - 2f),
                kotlin.math.floor(state.bodies.minOf { it.first.top } - 2f),
                kotlin.math.ceil(state.bodies.maxOf { it.first.right } + 2f),
                kotlin.math.ceil(state.bodies.maxOf { it.first.bottom } + 2f))
            val contourProbe = ShadowFieldProbe(listOf(contour), 1f / 255f)
            val contourResult = contourProbe.compare(actual, reference, actual.copy(image = blank, offscreen = true))
            shadowOracleEvidence("native-cutout-contour-$delta", actual, reference, contourResult)
            // Declared 2026-11-08 BEFORE running: at most one 8-bit quantum, across EVERY contour pixel.
            contourProbe.assertWithin(contourResult, 1f / 255f, "native destination mask delta=$delta")
        }
    } } finally { rule.mainClock.autoAdvance = true }
}
