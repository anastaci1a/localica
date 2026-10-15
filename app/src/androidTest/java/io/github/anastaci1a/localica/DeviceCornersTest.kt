package io.github.anastaci1a.localica

import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.DeviceCorners
import io.github.anastaci1a.localica.ui.deviceCorners
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class DeviceCornersTest {
    @get:Rule val compose = createComposeRule()
    private val density = Density(2f)

    @Test fun unavailableGeometryNeverSynthesizesRoundedCorners() {
        assertEquals(DeviceCorners(), deviceCorners(null, density))
        if (Build.VERSION.SDK_INT < 31) {
            lateinit var view: android.view.View
            compose.setContent { view = LocalView.current }
            compose.runOnIdle {
                val insets = requireNotNull(view.rootWindowInsets)
                assertEquals(DeviceCorners(), deviceCorners(insets, density))
            }
        }
    }

    @Test fun reportedCornersPreserveAbsenceAsymmetryAndWindowUpdates() {
        if (Build.VERSION.SDK_INT >= 31) {
            val empty = WindowInsets.Builder().build()
            assertEquals(DeviceCorners(), deviceCorners(empty, density))
            val rounded = WindowInsets.Builder()
                .setRoundedCorner(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, 40, 40, 40))
                .setRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, 60, 540, 940))
                .build()
            assertEquals(DeviceCorners(20.dp, 0.dp, 30.dp, 0.dp), deviceCorners(rounded, density))
            assertEquals("A later square window cannot retain the old device's radii", DeviceCorners(), deviceCorners(empty, density))
        }
    }

    @Test fun expansionInterpolatesToPhysicalCornersIncludingSquareEndpoints() {
        fun radii(corners: DeviceCorners, progress: Float): List<Float> {
            val outline = corners.shape(24.dp, progress).createOutline(Size(600f, 1000f), LayoutDirection.Ltr, density)
            // Compose may optimize an all-zero rounded shape to a rectangle.
            if (outline is Outline.Rectangle) return listOf(0f, 0f, 0f, 0f)
            val rect = (outline as Outline.Rounded).roundRect
            return listOf(rect.topLeftCornerRadius.x, rect.topRightCornerRadius.x,
                rect.bottomRightCornerRadius.x, rect.bottomLeftCornerRadius.x)
        }
        val asymmetric = DeviceCorners(20.dp, 0.dp, 30.dp, 0.dp)
        assertEquals(listOf(48f, 48f, 48f, 48f), radii(asymmetric, 0f))
        assertEquals(listOf(44f, 24f, 54f, 24f), radii(asymmetric, .5f))
        assertEquals(listOf(40f, 0f, 60f, 0f), radii(asymmetric, 1f))
        assertEquals(listOf(0f, 0f, 0f, 0f), radii(DeviceCorners(), 1f))
        assertEquals(radii(asymmetric, 0f), radii(asymmetric, -1f))
        assertEquals(radii(asymmetric, 1f), radii(asymmetric, 2f))
    }
}
