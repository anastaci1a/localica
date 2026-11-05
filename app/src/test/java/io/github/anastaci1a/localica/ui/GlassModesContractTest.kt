package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassModesContractTest {
    @Test fun onlyNormalAndNoneAreExposed() {
        assertEquals(listOf("Normal", "None"), GlassRendererMode.entries.map { it.name })
        assertEquals(listOf("Normal", "None"), GlassRendererMode.entries.map { it.label })
    }
    @Test fun normalIsTheDefault() {
        assertEquals("Normal", GlassAppearance().renderer.name)
    }
}
