package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Historical composition scheme versus draw fields and event reconstruction at every fraction. */
class PlayerThemePresentationEqualityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun allFractionsRetainDrawAndCompleteEventColors() {
        var phase by mutableFloatStateOf(0f)
        var ambient: ColorScheme? = null
        var expected: ColorScheme? = null
        val foreground = Color(.17f, .42f, .71f, .83f)
        val target = Color(.81f, .32f, .11f)
        val bottom = Color(.09f, .12f, .31f)
        val middle = Color(.21f, .37f, .51f)
        val top = Color(.47f, .28f, .19f)
        compose.setContent { LocalicaTheme {
            val base = MaterialTheme.colorScheme
            SideEffect { ambient = base }
            GlassPanelTheme(phase) {
                // Frozen former PresentTheme arithmetic, outside the candidate owner.
                val old = MaterialTheme.colorScheme.copy(onSurface = foreground,
                    onSurfaceVariant = foreground.copy(alpha = .72f), primary = lerp(base.primary, target, phase),
                    surface = lerp(base.surface, bottom, phase), surfaceContainer = lerp(base.surfaceContainer, middle, phase),
                    surfaceContainerHigh = lerp(base.surfaceContainerHigh, middle, phase),
                    surfaceContainerHighest = lerp(base.surfaceContainerHighest, top, phase))
                SideEffect { expected = old }
            }
        } }
        compose.waitForIdle()
        val base = checkNotNull(ambient)
        val owner = PlayerDrawTheme(base)
        for (i in 0..256) {
            val p = i / 256f
            compose.runOnIdle { phase = p }
            compose.waitForIdle()
            owner.present(PlayerThemePresentation(base, p, foreground, target, p, bottom, middle, top))
            val old = checkNotNull(expected)
            assertEquals("foreground $p", old.onSurface, owner.foreground())
            assertEquals("secondary $p", old.onSurfaceVariant, owner.secondary())
            assertEquals("loading base $p", old.surfaceContainer, owner.container())
            assertEquals("tint polarity input $p", old.surface, owner.surface())
            assertEquals("cancel error $p", old.error, owner.error())
            assertEquals("cancel onError $p", old.onError, owner.onError())
            assertEquals("complete event scheme $p", fields(old), fields(owner.colors))
            assertSame("continuous presentation cannot publish Material colors", base, owner.materialColors)
        }
    }

    @Test fun eventsAndDrawObserveOnlyAppliedSnapshotsIncludingAbortedUpdates() {
        lateinit var base: ColorScheme
        compose.setContent { LocalicaTheme { base = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        fun frame(p: Float) = PlayerThemePresentation(base, p, lerp(Color.Black, Color.White, p),
            Color.White, p, Color.Blue, Color.Green, Color.Red)
        val owner = PlayerDrawTheme(base)
        owner.present(frame(.2f))
        val accepted = fields(owner.colors)
        val pending = Snapshot.takeMutableSnapshot()
        try {
            pending.enter {
                owner.present(frame(.8f))
                assertNotEquals(accepted, fields(owner.colors))
            }
            assertEquals("unapplied event", accepted, fields(owner.colors))
            assertEquals("unapplied draw", frame(.2f).foreground, owner.foreground())
            pending.apply().check()
            assertEquals("next applied event", fields(frame(.8f).colors()), fields(owner.colors))
        } finally { pending.dispose() }
        val rejected = Snapshot.takeMutableSnapshot()
        try { rejected.enter { owner.present(frame(.4f)) } } finally { rejected.dispose() }
        assertEquals("aborted publication", fields(frame(.8f).colors()), fields(owner.colors))
    }

    @Test fun hoveredCancelTargetMatchesHistoricalTintThroughInterruptedPolarity() {
        var mix by mutableFloatStateOf(0f)
        var hover by mutableStateOf(true)
        var candidate by mutableStateOf(false)
        var epoch by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            val base = MaterialTheme.colorScheme
            val owner = remember { PlayerDrawTheme(base) }
            val frame = PlayerThemePresentation(base, mix, Color.Black, Color.White, .5f,
                Color.Blue, Color.Green, Color.Red)
            owner.present(frame)
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.None, contrast = 0f)) {
                key(candidate, epoch) {
                    Box(Modifier.size(96.dp).background(Color(0xFF8293A4)).testTag("cancel-consumer")) {
                        if (candidate) CompositionLocalProvider(LocalPlayerDrawTheme provides owner) {
                            DragCancelTarget(hover, "seek")
                        } else GlassPanelTheme(mix) {
                            val old = MaterialTheme.colorScheme.copy(onSurface = Color.Black,
                                onSurfaceVariant = Color.Black.copy(alpha = .72f))
                            MaterialTheme(colorScheme = old) {
                                CompositionLocalProvider(LocalContentColor provides old.onSurface) { DragCancelTarget(hover, "seek") }
                            }
                        }
                    }
                }
            }
        } }
        data class Step(val mix: Float, val hover: Boolean, val frames: Int)
        val steps = listOf(Step(0f,true,1), Step(.5f,true,1), Step(.95f,true,3), Step(1f,true,2),
            Step(.4f,true,2), Step(.8f,false,2), Step(1f,true,3), Step(.1f,true,2))
        fun arm(value: Boolean): List<IntArray> {
            compose.runOnIdle { candidate=value; epoch++; mix=0f; hover=true }
            return steps.map { step ->
                compose.runOnIdle { mix=step.mix; hover=step.hover }
                repeat(step.frames) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                val image=compose.onNodeWithTag("cancel-consumer").captureToImage().toPixelMap()
                IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() }
            }
        }
        try { arm(false).zip(arm(true)).forEachIndexed { i,(old,new) -> assertArrayEquals("hovered target step $i",old,new) } }
        finally { compose.mainClock.autoAdvance=true }
    }

    @Test fun outerPanelDialogRetainsPanelInkAcrossPlayerForegroundReversal() {
        var phase by mutableFloatStateOf(.5f)
        var panel = Color.Unspecified
        var popup = Color.Unspecified
        var full = Color.Unspecified
        compose.setContent { LocalicaTheme {
            val base=MaterialTheme.colorScheme
            val owner=remember { PlayerDrawTheme(base) }
            owner.present(PlayerThemePresentation(base,1f,lerp(Color.Black,Color.White,phase),Color.White,phase,
                Color.Blue,Color.Green,Color.Red))
            PlayerMaterialTheme(owner) { CompositionLocalProvider(LocalPlayerDrawTheme provides owner) {
                // This is the viewer/configure boundary: panel ink remains white even if full ink is dark.
                PlayerPanelMaterialTheme(owner) {
                    val ink=LocalContentColor.current
                    SideEffect { panel=ink; full=owner.foreground() }
                    GlassDialog({}) {
                        val dialogInk=LocalContentColor.current
                        SideEffect { popup=dialogInk }
                        Box(Modifier.size(80.dp))
                    }
                }
            } }
        } }
        for(p in listOf(.5f,.1f,.8f,.2f)) {
            compose.runOnIdle { phase=p }; compose.waitForIdle()
            assertEquals("outer panel $p",Color.White,panel)
            assertEquals("actual outer dialog $p",Color.White,popup)
            assertNotEquals("counterexample requires distinct full-player ink",panel,full)
        }
    }

    private fun fields(c: ColorScheme) = listOf(c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer,
        c.inversePrimary, c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer,
        c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer, c.background, c.onBackground,
        c.surface, c.onSurface, c.surfaceVariant, c.onSurfaceVariant, c.surfaceTint, c.inverseSurface,
        c.inverseOnSurface, c.error, c.onError, c.errorContainer, c.onErrorContainer, c.outline, c.outlineVariant,
        c.scrim, c.surfaceBright, c.surfaceDim, c.surfaceContainer, c.surfaceContainerHigh,
        c.surfaceContainerHighest, c.surfaceContainerLow, c.surfaceContainerLowest)
}
