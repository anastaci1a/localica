package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.InterfaceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** #854 review (rule 12): the slider and the settings summary show the speed through one label, clamped like the setting. */
class AnimationSpeedLabelTest {
    @Test fun everyTenthHasOneLabelAndOutOfRangeValuesClamp() {
        for (tenths in 5..15) assertEquals("${tenths / 10}.${tenths % 10}×", InterfaceSettings(animationSpeedTenths = tenths).animationSpeedLabel)
        assertEquals("0.5×", InterfaceSettings(animationSpeedTenths = 1).animationSpeedLabel)
        assertEquals("1.5×", InterfaceSettings(animationSpeedTenths = 40).animationSpeedLabel)
    }
}
