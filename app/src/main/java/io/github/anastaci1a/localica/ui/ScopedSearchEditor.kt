package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** Editor lifetime is independent of the route keys that save completed result positions. */
internal class ScopedSearchEditorSession {
    internal val focus = FocusRequester()
    internal var active = true
        private set
    internal var focused by mutableStateOf(false)
        private set
    internal fun retire() { active = false; focused = false }
    internal val content = movableContentOf<ScopedSearchEditorParameters> { input ->
        ScopedSearchEditor(input) { focused = active && it }
    }
}

internal data class ScopedSearchEditorParameters(
    val value: String,
    val onValue: (String) -> Unit,
    val label: String,
    val focus: FocusRequester,
    val onSearch: () -> Unit,
)

@Composable internal fun ScopedSearchEditor(input: ScopedSearchEditorParameters, onFocus: (Boolean) -> Unit = {}) {
    ClearableTextField(input.value, input.onValue, singleLine = true,
        placeholder = { Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { input.onSearch() }),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(input.focus).onFocusChanged { onFocus(it.isFocused) })
}
