package io.github.anastaci1a.localica


import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class IconRegistryVisualTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun output(name: String) = File(nativeEvidenceDirectory("icon-registry-697", context.getExternalFilesDir(null)), "api${Build.VERSION.SDK_INT}-$name.png")
    private fun render(painter: Painter, pixels: Int): Bitmap {
        val image = ImageBitmap(pixels, pixels)
        CanvasDrawScope().draw(Density(pixels / 24f), LayoutDirection.Ltr, Canvas(image), Size(pixels.toFloat(), pixels.toFloat())) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(Color.Black)) }
        }
        return image.asAndroidBitmap()
    }

    @Test fun everyDirectionMatchesIndependentFlattenedGeometryAndExportRoundtrip() {
        IconPacks.reset(context)
        val expected = linkedMapOf(
            "action.arrow_down" to "M12,3V20 M5,13L12,20L19,13",
            "action.arrow_up" to "M12,21V4 M19,11L12,4L5,11",
            "action.arrow_left" to "M21,12H4 M11,5L4,12L11,19",
            "action.arrow_right" to "M3,12H20 M13,19L20,12L13,5",
            "action.chevron_left" to "M15,5L8,12L15,19",
            "action.chevron_right" to "M9,19L16,12L9,5",
            "action.chevron_up" to "M19,15L12,8L5,15",
            "action.chevron_down" to "M5,9L12,16L19,9",
        )
        val painters = linkedMapOf<String, List<Painter>>()
        compose.setContent {
            expected.forEach { (id, path) ->
                val icon = requireNotNull(AppIcon.byId(id))
                val svg = """<svg viewBox="0 0 24 24"><path fill="none" stroke="black" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="$path"/></svg>"""
                val builtin = packIconPainter(icon, null)
                val flat = packIconPainter(icon, null, SvgIconParser.parse(svg.toByteArray()).toImageVector())
                val roundtrip = packIconPainter(icon, null, SvgIconParser.parse(builtinSvg(context, icon)).toImageVector())
                SideEffect { painters[id] = listOf(builtin, flat, roundtrip) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val sheet = Bitmap.createBitmap(640, expected.size * 100, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sheet).apply { drawColor(android.graphics.Color.WHITE) }
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 16f }
            painters.entries.forEachIndexed { row, (id, values) ->
                canvas.drawText(id, 8f, row * 100f + 32, paint)
                for (px in listOf(24, 48)) {
                    val images = values.map { render(it, px) }
                    for (other in images.drop(1)) {
                        var delta = 0L
                        for (y in 0 until px) for (x in 0 until px)
                            delta += kotlin.math.abs((images[0].getPixel(x, y) ushr 24) - (other.getPixel(x, y) ushr 24))
                        assertTrue("$id $px px independent orientation/roundtrip error $delta", delta <= px * px)
                    }
                    images.forEachIndexed { column, image ->
                        canvas.drawBitmap(image, 230f + column * 120 + if (px == 48) 32 else 0, row * 100f + 12, null)
                        image.recycle()
                    }
                }
            }
            output("directions-builtin-flat-roundtrip").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
        }
    }

    @Test fun formerMoreSitesUseHorizontalEllipsisInTheirActualControlsAndUtilityFace() {
        IconPacks.reset(context)
        val queue = ListeningQueue(name = "Icon witness", entries = emptyList())
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        var face by mutableStateOf<(@Composable () -> Unit)?>(null)
        compose.setContent { LocalicaTheme {
            val backdrop = rememberFrostedBackdrop(Unit, dark = false)
            val anchor = rememberGlassAnchor()
            Column(Modifier.fillMaxSize().background(Color(0xffeeeeee)).padding(12.dp)) {
                Text("QueueScreen — Queue actions")
                Box(Modifier.fillMaxWidth().height(240.dp)) { QueueScreen(state, null) }
                Text("PlayerActionStrip — More actions")
                Box(Modifier.fillMaxWidth().height(72.dp)) {
                    PlayerActionStrip(PlayerActionSettings(slots = mapOf(ActionSlot(0, 0) to PlayerAction.More)), { it.label }, {}, {})
                }
                Text("PopupActionPill — live")
                PopupActionPill(anchor, backdrop, Modifier.width(104.dp).height(48.dp), onSearch = {}, onMore = {})
                Text("PopupActionPill — retained descriptor")
                Box(Modifier.width(104.dp).height(48.dp)) { face?.invoke() }
            }
            SideEffect { if (face == null) face = anchor.face }
        } }
        compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
        assertEquals(AppIcon.ActionEllipsis, PlayerAction.More.icon)
        val image = compose.onRoot().captureNativeFrame().asAndroidBitmap()
        output("former-more-controls").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
