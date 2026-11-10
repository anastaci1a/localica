package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * #705: at one fixed shared shimmer phase, a held row's text-bar sweep on a dark artwork page
 * moves its bar by Library's magnitude toward the page ink. Screen-level infinite transitions are
 * suspended under test, so the phase is supplied directly to the same shared painter.
 */
class LoadingSweepContrastTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun darkArtworkPageSweepMatchesLibrarySweepMagnitude() {
        ArtworkCache.invalidate()
        val uris = listOf("content://test.sweep/library-705", "content://test.sweep/collection-705")
        ArtworkColors.publish(uris.associateWith { 0xFF786860.toInt() })
        val transit = ArtworkTransit()
        val hold = CoroutineScope(Dispatchers.Unconfined).launch { transit.during(true) { awaitCancellation() } }
        val items = uris.mapIndexed { index, uri -> BrowserItem(MediaRef(MediaKind.Playlist, "sweep-705-$index"),
            "${"AB"[index]} held title that is long enough to carry the sweep", null, emptyList(), coverUri = uri) }
        val style = BrowseStyle(listSize = ListSize.Normal, extraInfo = ExtraInfo.None)
        val phase = mutableFloatStateOf(-1f)
        val page = Color(0xFF312A25)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtworkTransit provides transit, LocalReducedMotion provides false,
                    LocalLoadingShimmer provides phase) {
                    LookaheadScope { Column {
                        Box(Modifier.size(360.dp, 80.dp).background(MaterialTheme.colorScheme.surface)) {
                            BrowseItemLayout(items[0], style, BrowseCellGeometry(1), this@LookaheadScope, true, {})
                        }
                        // The dark artwork page exactly as BrowserScreen supplies it.
                        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White,
                            onSurfaceVariant = Color.White.copy(alpha = .65f), primary = Color.White, surface = page)) {
                            CompositionLocalProvider(LocalContentColor provides Color.White,
                                LocalLoadingMaterialColor provides Color.White.copy(alpha = .04f)) {
                                Box(Modifier.size(360.dp, 80.dp).background(page)) {
                                    BrowseItemLayout(items[1], style, BrowseCellGeometry(1), this@LookaheadScope, true, {})
                                }
                            }
                        }
                    } }
                }
            } }
            compose.waitForIdle()
            fun luma(item: BrowserItem): Float {
                val pixels = compose.onNodeWithTag("browse-text-${item.key}", true).captureToImage().toPixelMap()
                // The sweep's peak sits at width * phase on the bar's centre line.
                val c = pixels[(pixels.width * .25f).toInt(), pixels.height / 2]
                return 255f * (.2126f * c.red + .7152f * c.green + .0722f * c.blue)
            }
            val rest = items.map(::luma)
            compose.runOnIdle { phase.floatValue = .25f }
            compose.waitForIdle()
            val swept = items.map(::luma)
            val library = swept[0] - rest[0]
            val collection = swept[1] - rest[1]
            assertTrue("Library sweep darkens toward its ink: rest=$rest swept=$swept", library < -8f)
            assertTrue("Dark page sweep lightens toward its ink: rest=$rest swept=$swept", collection > 0f)
            assertEquals("Sweep magnitude matches Library: rest=$rest swept=$swept", abs(library), abs(collection), 3f)
        } finally {
            hold.cancel()
            ArtworkCache.invalidate()
            ArtworkColors.publish(emptyMap())
        }
    }
}
