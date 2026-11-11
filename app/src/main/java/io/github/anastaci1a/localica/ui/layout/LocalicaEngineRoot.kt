package io.github.anastaci1a.localica.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.engine.EngineContext
import io.github.anastaci1a.localica.ui.engine.ExpansionBasis
import io.github.anastaci1a.localica.ui.engine.LocalEngineContext
import io.github.anastaci1a.localica.ui.spec.ResolvedUiSpec

/** The layout boundary exposes measured travel without giving a screen Engine ownership. */
internal fun expansionReferenceTravel(basis: ExpansionBasis): Float = basis.edgeTravelPx

/** One root record retains the measured spec and platform policy as separate inputs. */
@Composable
internal fun ProvideLocalicaEngineContext(refreshHz: Float, referenceTravel: Float,
    resolvedSpec: ResolvedUiSpec, durationScale: Float, content: @Composable () -> Unit) {
    val engine = remember(refreshHz, referenceTravel, resolvedSpec, durationScale) {
        EngineContext(1f / refreshHz, referenceTravel, resolvedSpec.spec.access, durationScale, resolvedSpec.spec.speed)
    }
    CompositionLocalProvider(LocalEngineContext provides engine, content = content)
}
