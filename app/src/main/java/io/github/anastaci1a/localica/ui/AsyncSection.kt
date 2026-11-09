package io.github.anastaci1a.localica.ui

import android.os.StrictMode
import android.os.Trace
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.BuildConfig
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Archive and catalog inputs are immutable snapshots. Comparing them must not walk their contents on main. */
private class AsyncIdentity(private val value: Any?) {
    override fun equals(other: Any?) = other is AsyncIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}

/** Cancels superseded requests; no stale value can become the result of a newer request. */
@Composable
internal fun <T : Any> rememberAsyncValue(vararg inputs: Any?, compute: () -> T): State<T?> {
    val keys = inputs.map(::AsyncIdentity).toTypedArray()
    val result = remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(result) {
        result.value = withContext(Dispatchers.Default) {
            if (BuildConfig.DEBUG) Trace.beginSection("Localica.AsyncSection.prepare")
            try {
                if (BuildConfig.DEBUG) StrictMode.noteSlowCall("Async section preparation must stay off main")
                compute()
            } finally { if (BuildConfig.DEBUG) Trace.endSection() }
        }
    }
    return result
}

/** One clock controls spinner exit and row reveal in settings, lists and popups. */
@Composable
internal fun <T : Any> AsyncSection(value: T?, modifier: Modifier = Modifier, rowCount: Int = 0, content: @Composable (T) -> Unit) {
    val reduced = LocalReducedMotion.current
    val spec = LocalUiSpec.current.spec
    val fade = (spec.motion.getValue(MotionProfile.ControlOff) as MotionProfileSpec).durationMs
    val spinner = remember { Animatable(if (value == null) 1f else 0f) }
    var presented by remember { mutableStateOf(value != null) }
    LaunchedEffect(value != null, reduced) {
        if (value == null) { presented = false; spinner.snapTo(1f) }
        else {
            if (reduced) spinner.snapTo(0f) else spinner.animateTo(0f, tween(fade))
            presented = true
        }
    }
    if (value == null || !presented) {
        Box(modifier.fillMaxWidth().height(spec.control.getValue(ControlSize.PreferenceRow))
            .padding(top = spec.space.getValue(Space.Base)).graphicsLayer { alpha = spinner.value }
            .testTag("async-section-loading"), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(Modifier.size(spec.control.getValue(ControlSize.SettingsIcon)))
        }
    } else {
        val clock = remember { Animatable(0f) }
        val duration = (BrowseLoadingMotion.delayMillis(0f, (rowCount - 1).coerceAtLeast(0) *
            spec.control.getValue(ControlSize.PreferenceRow).value) + BrowseLoadingMotion.FadeMillis).toInt()
        LaunchedEffect(clock, reduced, rowCount) {
            if (reduced || rowCount == 0) clock.snapTo(Float.MAX_VALUE)
            else clock.animateTo(duration.toFloat(), tween(duration, easing = LinearEasing))
        }
        CompositionLocalProvider(LocalAsyncSectionClock provides clock.takeIf { rowCount > 0 }) { content(value) }
    }
}

private val LocalAsyncSectionClock = staticCompositionLocalOf<Animatable<Float, *>?> { null }

/** The shared loading wave changes drawing only; rows retain their final geometry. */
@Composable
internal fun AsyncSectionRow(index: Int, content: @Composable () -> Unit) {
    val clock = LocalAsyncSectionClock.current
    val rowHeight = LocalUiSpec.current.spec.control.getValue(ControlSize.PreferenceRow).value
    val reduced = LocalReducedMotion.current
    val delay = BrowseLoadingMotion.delayMillis(0f, index * rowHeight)
    val opacity = remember(clock, delay, reduced) { derivedStateOf {
        if (reduced || clock == null) 1f else BrowseLoadingMotion.fade(clock.value, delay)
    } }
    Column(Modifier.graphicsLayer { alpha = opacity.value }, verticalArrangement = Arrangement.spacedBy(LocalUiSpec.current.spec.space.getValue(Space.Base))) { content() }
}

/** The final text line and loading material reserve the same height in every host. */
@Composable
internal fun AsyncValueText(value: String?, placeholder: String, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) {
    val corner = LocalUiSpec.current.px(Space.Tight)
    Box(modifier) {
        val textSemantics = if (value == null) Modifier.clearAndSetSemantics { } else Modifier
        androidx.compose.material3.Text(value ?: placeholder, textSemantics.graphicsLayer { alpha = if (value == null) 0f else 1f },
            style = style, maxLines = maxLines, minLines = minLines, softWrap = softWrap, overflow = overflow)
        if (value == null) BrowseLoadingShape(Modifier.matchParentSize().testTag("async-value-placeholder"), { corner })
    }
}
