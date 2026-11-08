package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*

class LyricsSourcePhaseTest {
    private fun mappings(test: (Curve,Curve)->Unit) {
        val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,4000f,1f/30)) as SpecResult.Accepted).spec
        EngineHarness(spec=spec).use { h ->
            val owner=PopupWindowMotion(h.clock,spec.expansion,"lyrics mappings",true,false)
            try { test(owner.preparationCurve,owner.popupCurve) } finally { owner.dispose() }
        }
    }


    @Test fun mappingHasSeparatedOverlapAndExactEndpoints() = mappings { preparation,popup ->
        assertEquals(0f, preparation.at(0f), 0f)
        assertEquals(0f, popup.at(0f), 0f)
        assertEquals(1f, preparation.at(1f), 0f)
        assertEquals(1f, popup.at(1f), 0f)
        assertTrue(preparation.at(.25f) > .5f)
        assertEquals(0f, popup.at(.25f), 0f)
        assertTrue(preparation.at(.3f) < 1f)
        assertTrue(popup.at(.3f) > 0f)
        assertEquals(1f, preparation.at(.4f), 0f)
    }

    @Test fun mappingIsMonotonicWithZeroScalarEndpointSlopes() = mappings { preparation,popup ->
        var lastPreparation = 0f
        var lastPopup = 0f
        for (i in 0..1000) {
            val q = i / 1000f
            val preparation = preparation.at(q)
            val popup = popup.at(q)
            assertTrue(preparation >= lastPreparation)
            assertTrue(popup >= lastPopup)
            lastPreparation = preparation
            lastPopup = popup
        }
        val h = .0001f
        assertEquals(0f, preparation.at(h) / h, .01f)
        assertEquals(0f, (1f - preparation.at(.4f - h)) / h, .01f)
        assertEquals(0f, popup.at(.25f + h) / h, .01f)
        assertEquals(0f, (1f - popup.at(1f - h)) / h, .01f)
    }

}
