package io.github.anastaci1a.localica.ui

import androidx.compose.animation.EnterExitState
import org.junit.Assert.*
import org.junit.Test

class PopupHostAlphaReaderTest {
    private val visible = EnterExitState.Visible
    private val exited = EnterExitState.PostExit
    private fun read(current: EnterExitState = visible, target: EnterExitState = visible,
        running: Boolean = false, seeking: Boolean = false, values: List<Pair<String, Any?>> = emptyList()) =
        readPopupHostAlpha(current, target, running, seeking, values)

    @Test fun stableVisibleWithoutAnAnimationUsesTheActualConstantOpacity() {
        assertEquals(1f, read(), 0f)
    }
    @Test fun movingFadeUsesTheActualBuiltInValue() {
        assertEquals(.375f, read(target = exited, running = true,
            values = listOf("Built-in alpha" to .375f)), 0f)
    }
    @Test fun missingAnimationDuringMotionOrSeekingFailsClosed() {
        assertThrows(IllegalStateException::class.java) { read(target = exited, running = true) }
        assertThrows(IllegalStateException::class.java) { read(running = true) }
        assertThrows(IllegalStateException::class.java) { read(seeking = true) }
        assertThrows(IllegalStateException::class.java) { read(current = exited, target = exited) }
        assertThrows(IllegalStateException::class.java) { read(current = EnterExitState.PreEnter) }
    }
    @Test fun aDifferentAlphaLabelCannotStandInForTheActualBuiltInFade() {
        assertThrows(IllegalStateException::class.java) {
            read(target = exited, values = listOf("Custom alpha" to .375f))
        }
    }
    @Test fun duplicateBuiltInAnimationsFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            read(values = listOf("Built-in alpha" to .5f, "Built-in alpha" to .75f))
        }
    }
    @Test fun malformedValuesFailClosed() {
        for (value in listOf(null, ".5", .5, Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            assertThrows(IllegalStateException::class.java) { read(values = listOf("Built-in alpha" to value)) }
        }
    }
}
