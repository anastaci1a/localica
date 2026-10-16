package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class SelectionPanelGeometryTest {
    @Test fun clearanceFollowsVisibleHeightAndSharedGapAtEveryFrame() {
        for (height in listOf(48f, 96f, 320f)) for (chrome in listOf(0f, 8f, 80f, 139.2f)) {
            for (frame in 0..220) {
                val progress = frame / 220f
                val geometry = SelectionPanelGeometry(height, progress, 8f)
                val panelTop = chrome + height - geometry.translation
                val controlBottom = chrome + geometry.clearance
                assertEquals(8f * progress, controlBottom - panelTop, .0001f)
                assertEquals(chrome + (height + 8f) * progress, controlBottom, .0001f)
            }
        }
    }
    @Test fun hiddenPanelReservesNothingRegardlessOfRetainedMeasurement() {
        for (height in listOf(0f, 48f, 320f)) {
            val geometry = SelectionPanelGeometry(height, 0f, 8f)
            assertEquals(0f, geometry.clearance, 0f)
            assertEquals(height, geometry.translation, 0f)
        }
    }
    @Test fun shownPanelHasStandardGapAndProgressIsBounded() {
        assertEquals(104f, SelectionPanelGeometry(96f, 1f, 8f).clearance, 0f)
        assertEquals(0f, SelectionPanelGeometry(96f, 1f, 8f).translation, 0f)
        assertEquals(104f, SelectionPanelGeometry(96f, 1.1f, 8f).clearance, 0f)
        assertEquals(0f, SelectionPanelGeometry(96f, -.1f, 8f).clearance, 0f)
    }
}
