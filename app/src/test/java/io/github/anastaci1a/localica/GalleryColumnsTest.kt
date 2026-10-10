package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test

class GalleryColumnsTest {
    @Test fun densitiesRemainDistinctAcrossPhoneAndTabletWidths() {
        for (width in listOf(320f, 360f, 411f, 480f, 600f, 720f, 1024f)) {
            val large = galleryColumns(Presentation.Large, width)
            val standard = galleryColumns(Presentation.Standard, width)
            val compact = galleryColumns(Presentation.Compact, width)
            assertTrue("Density at $width", large < standard && standard < compact)
            assertTrue("Compact items retain a full touch target", width / compact >= 100f)
            assertEquals(1, galleryColumns(Presentation.List, width))
        }
    }
}
