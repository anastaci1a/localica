package io.github.anastaci1a.localica.ui

import android.os.StrictMode
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.anastaci1a.localica.BuildConfig
import io.github.anastaci1a.localica.ui.layout.*
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
            if (BuildConfig.DEBUG && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                StrictMode.noteSlowCall("Async section preparation on main")
                error("Async section preparation must stay off main")
            }
            compute()
        }
    }
    return result
}

/** Existing control-state fade and browse loading wave own every animated value here. */
@Composable
internal fun <T : Any> AsyncSection(value: T?, modifier: Modifier = Modifier, rowCount: Int = 0, content: @Composable (T) -> Unit) {
    val reduced = LocalReducedMotion.current
    val spinner = animateControlStateFloat(if (value == null) 1f else 0f, value == null, "Async section")
    val presented by remember(value != null, spinner, reduced) { derivedStateOf { value != null && (reduced || spinner.value == 0f) } }
    if (value == null || !presented) {
        val opacity = remember(spinner) { ArtworkTextReveal { spinner.value }.apply { hold(true) } }
        AsyncSpinnerSlot(modifier.artworkTextReveal(opacity, { null }, { 0f }, skeleton = false).testTag("async-section-loading"))
    } else {
        val rowHeight = StockUiSpec.control.getValue(ControlSize.PreferenceRow).value
        val reveal = if (rowCount == 0) null else rememberBrowseLoadingReveal(AsyncIdentity(value), true, reduced, 1,
            0f, rowCount * rowHeight, reduced)
        CompositionLocalProvider(LocalAsyncSectionReveal provides reveal) { content(value) }
    }
}

private val LocalAsyncSectionReveal = staticCompositionLocalOf<BrowseLoadingReveal?> { null }

/** Reuses the same draw-only reveal and the page's existing Compact row/action gap. */
@Composable
internal fun AsyncSectionRow(index: Int, content: @Composable () -> Unit) {
    val wave = LocalAsyncSectionReveal.current
    val cell = remember(wave, index) { wave?.cell(index) }
    val rowHeight = StockUiSpec.control.getValue(ControlSize.PreferenceRow).value
    SideEffect { cell?.place(0f, index * rowHeight) }
    val opacity = remember(cell) { cell?.let { source -> ArtworkTextReveal { source.alpha }.apply { hold(true) } } }
    AsyncSectionRowSlot(Modifier.artworkTextReveal(opacity, { null }, { 0f }, skeleton = false), content)
}

/** Null means pending; callers provide the settled absent value. Ready text keeps Text's inherited contract. */
@Composable
internal fun AsyncValueText(value: String?, placeholder: String, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) {
    val corner = with(LocalDensity.current) { StockUiSpec.space.getValue(Space.Tight).toPx() }
    AsyncValueSlot(modifier, text = {
        val textSemantics = if (value == null) Modifier.clearAndSetSemantics { } else Modifier
        val hidden = if (value == null) remember { ArtworkTextReveal { 0f }.apply { hold(true) } } else null
        androidx.compose.material3.Text(value ?: placeholder,
            textSemantics.artworkTextReveal(hidden, { null }, { 0f }, skeleton = false),
            style = style, maxLines = maxLines, minLines = minLines, softWrap = softWrap, overflow = overflow)
    }, placeholder = {
        if (value == null) BrowseLoadingShape(Modifier.matchParentSize().testTag("async-value-placeholder"), { corner })
    })
}
