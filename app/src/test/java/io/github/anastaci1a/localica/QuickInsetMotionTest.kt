package io.github.anastaci1a.localica

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.engine.quickInsetAnimationSpec
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class QuickInsetMotionTest {
    @Test fun engineAdoptionKeepsEverySampleAndVelocityBitIdentical() {
        for (speed in listOf(1f, 4f / 3f, 2f)) for (reduced in listOf(false, true)) {
            for ((start, end) in listOf(0f to 1f, 1f to 0f, .37f to 1f, .73f to 0f)) {
                val oldSpec = if (reduced) snap<Float>() else tween((220f / speed).roundToInt().coerceAtLeast(1), easing = FastOutSlowInEasing)
                val before = TargetBasedAnimation(oldSpec, Float.VectorConverter, start, end)
                val after = TargetBasedAnimation(quickInsetAnimationSpec(StockUiSpec, reduced, speed), Float.VectorConverter, start, end)
                assertEquals(before.durationNanos, after.durationNanos)
                for (sample in 0..1000) {
                    val time = before.durationNanos * sample / 1000
                    assertEquals("Value at $speed/$reduced/$start/$end/$sample", before.getValueFromNanos(time).toRawBits(), after.getValueFromNanos(time).toRawBits())
                    assertEquals("Velocity at $speed/$reduced/$start/$end/$sample", before.getVelocityVectorFromNanos(time).value.toRawBits(), after.getVelocityVectorFromNanos(time).value.toRawBits())
                }
            }
        }
    }
}
