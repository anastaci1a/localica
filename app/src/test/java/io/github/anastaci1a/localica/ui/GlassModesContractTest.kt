package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassModesContractTest {
    @Test fun twoModesHaveDistinctStableNames() {
        assertEquals(listOf("Normal", "FlatNone"), GlassRendererMode.entries.map { it.name })
        assertEquals(listOf("Normal", "None"), GlassRendererMode.entries.map { it.label })
    }
    @Test fun retiredSamplingModesRestoreNormal() {
        for (stored in listOf("SingleToneBlur", "None"))
            assertEquals(GlassRendererMode.Normal, GlassRendererMode.fromStored(stored))
    }
    @Test fun normalIsTheDefault() {
        assertEquals("Normal", GlassAppearance().renderer.name)
    }
    @Test fun materialSpecificationReportsEveryCurrentRenderer() {
        for (mode in GlassRendererMode.entries) {
            val settings = io.github.anastaci1a.localica.navigation.InterfaceSettings(glass = GlassAppearance(renderer = mode))
            val accepted = io.github.anastaci1a.localica.ui.spec.uiSpec(settings) as io.github.anastaci1a.localica.ui.spec.SpecResult.Accepted
            for (tokens in accepted.spec.material.values) assertEquals(mode.name, tokens.mode.name)
        }
    }
}
