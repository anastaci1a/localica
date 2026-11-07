package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*

/** The U11.00 root record (EngineContext + measured geometry) for fixtures that compose Engine owners outside LocalicaScreen. */
@Composable fun EngineRecord(reduced: Boolean = false, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density, reduced) { uiSpec(InterfaceSettings(reducedMotion = reduced), ExpansionGeometryDomain(1000f, 3000f, 1f / 60), density) }
    CompositionLocalProvider(LocalEngineContext provides EngineContext(1f / 60, 1000f, spec.spec.access), LocalUiSpec provides spec, content = content)
}

/** The engine clock under the virtual test frame clock: intents are stamped at the test clock's current time, which is its frame
 * time base. Production's FrameBase stamps virtual intents at the engine's own last frame, which goes stale while no motion runs,
 * so the first frame after an idle period jumped by the whole gap. Same wiring as PlayerReleaseContinuityTest. */
@Composable internal fun VirtualUiClock(time: MainTestClock, content: @Composable () -> Unit) {
    val clock = remember { UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it }
        override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame) }, { time.currentTime * 1_000_000 }) }
    LaunchedEffect(clock) { clock.run() }
    CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock, content = content)
}

/** U03.01c: a theme with the root record, for fixtures composing Engine-owned shapes (the lyrics split pill) outside LocalicaScreen. */
@Composable internal fun EngineTheme(clear: Boolean = false, reduced: Boolean = false, content: @Composable () -> Unit) =
    if (clear) ClearGlassTheme { EngineRecord(reduced, content) } else io.github.anastaci1a.localica.ui.LocalicaTheme { EngineRecord(reduced, content) }
